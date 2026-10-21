package com.knk.manyak.user.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.repository.AdminAccountRepository
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@Service
class AdminSuspensionService(
    private val users: UserRepository,
    private val accounts: AdminAccountRepository,
    private val audits: AdminAuditService,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun change(adminUserId: Long, memberId: UUID, reason: String, suspend: Boolean): AdminSuspensionResponse {
        val user = users.findByPublicIdForUpdate(memberId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.")
        if (suspend && accounts.existsById(user.id)) {
            throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.ADMIN_ACCOUNT_PROTECTED,
                "관리자 역할이 있는 회원은 정지할 수 없습니다.")
        }
        val expected = if (suspend) UserStatus.ACTIVE else UserStatus.SUSPENDED
        if (user.status != expected) throw ResponseStatusException(HttpStatus.CONFLICT, "계정 상태를 전환할 수 없습니다.")
        val before = mapper.valueToTree<tools.jackson.databind.JsonNode>(mapOf("status" to user.status.name))
        user.status = if (suspend) UserStatus.SUSPENDED else UserStatus.ACTIVE
        // @PreUpdate가 기록한 실제 전환 시각을 응답하며, 감사 저장 실패 시 이 변경도 롤백한다.
        users.flush()
        audits.recordChange(adminUserId, if (suspend) AdminAuditAction.MEMBER_SUSPENDED else AdminAuditAction.MEMBER_UNSUSPENDED,
            memberId.toString(), reason, before, mapper.valueToTree(mapOf("status" to user.status.name)))
        return AdminSuspensionResponse(user.publicId, user.nickname, user.status, user.updatedAt)
    }
}

data class AdminSuspensionResponse(val memberId: UUID, val nickname: String, val status: UserStatus, val updatedAt: Instant)
