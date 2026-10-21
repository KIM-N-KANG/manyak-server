package com.knk.manyak.admin.service

import com.knk.manyak.admin.entity.*
import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.admin.repository.AdminChangeActorRepository
import jakarta.persistence.EntityManager
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import com.knk.manyak.global.observability.MdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode

@Service
class AdminAuditService(
    private val logs: AdminAuditLogRepository,
    transactionManager: PlatformTransactionManager,
    private val actors: AdminChangeActorRepository,
    private val entityManager: EntityManager,
) {
    private val accessTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isReadOnly = false
    }
    private val log = LoggerFactory.getLogger(javaClass)

    /** 호출 도메인이 확정한 변경과 함께 커밋되며 트랜잭션 밖에서는 호출할 수 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordChange(
        adminUserId: Long,
        action: AdminAuditAction,
        targetId: String,
        reason: String,
        before: JsonNode? = null,
        after: JsonNode? = null,
    ): AdminAuditLog {
        require(action.category == AdminAuditCategory.CHANGE)
        require(reason.isNotBlank() && reason.length <= 500)
        val beforeJson = before?.toString()?.also { require(it.length <= 4000) }
        val afterJson = after?.toString()?.also { require(it.length <= 4000) }
        // 잠금 순서: 도메인의 대상 잠금/변경(flush) → 처리자 users → admin_accounts → 감사 INSERT.
        // 도메인이 탈퇴와 순서를 맞추려고 처리자를 먼저 잠근 경우에는 그 잠금을 재사용한다.
        // 감사는 변경의 마지막 단계다. 이 호출 뒤 새 대상 행을 잠그거나 변경해서는 안 된다.
        // 처리자 잠금을 기다리면 A가 B를, B가 A를 대상으로 바꿀 때 교차 대기할 수 있다.
        // NOWAIT로 두 재검증 잠금은 기다리지 않고 403으로 롤백한다. flush 이후에는 대상 UPDATE가
        // 지연 실행되지 않고, 감사 INSERT의 유일한 회원 FK는 이미 잠근 처리자라 새 대기 고리가 없다.
        // 탈퇴도 같은 users 행을 먼저 FOR UPDATE 하므로, 먼저 잡은 트랜잭션의 커밋 순서를 따른다.
        return com.knk.manyak.auth.consent.SocialAuthPrivacyFilter.safely {
            entityManager.flush()
            val allowed = try { actors.lockActiveAdmin(adminUserId) }
                catch (_: PessimisticLockingFailureException) { false }
            if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 없습니다.")
            logs.save(row(adminUserId, action, targetId, reason, beforeJson, afterJson))
        }
    }

    /** 독립 트랜잭션의 커밋 실패까지 호출자에게 전파해 조회 응답도 실패하게 한다. */
    fun recordAccess(adminUserId: Long, action: AdminAuditAction, targetId: String): AdminAuditLog {
        require(action.category == AdminAuditCategory.ACCESS)
        val row = row(adminUserId, action, targetId)
        return try {
            com.knk.manyak.auth.consent.SocialAuthPrivacyFilter.safely {
                requireNotNull(accessTransaction.execute { logs.save(row) })
            }
        } catch (ex: RuntimeException) {
            log.warn("admin_access_record_failed action={}, exception={}", action, ex.javaClass.simpleName)
            throw ex
        }
    }

    private fun row(adminUserId: Long, action: AdminAuditAction, targetId: String, reason: String? = null,
                    before: String? = null, after: String? = null): AdminAuditLog {
        require(targetId.isNotBlank() && targetId.length <= 255)
        // 상관관계 필터가 확정한 값만 사용한다. 별도 생성하면 응답 헤더와 달라질 수 있다.
        val requestId = requireNotNull(MDC.get(MdcKeys.REQUEST_ID)) { "요청 ID가 없습니다." }
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(requestId))
        return AdminAuditLog(adminUserId = adminUserId, category = action.category, action = action,
            targetType = action.targetType, targetId = targetId, reason = reason,
            beforeState = before, afterState = after, requestId = requestId)
    }
}
