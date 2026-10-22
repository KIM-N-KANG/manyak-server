package com.knk.manyak.credit.service

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.dto.*
import com.knk.manyak.credit.entity.CreditAdminAdjustment
import com.knk.manyak.credit.repository.CreditAdminAdjustmentRepository
import com.knk.manyak.credit.repository.CreditAdminLocks
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

@Service
class AdminCreditAdjustmentService(
    private val adjustments: CreditAdminAdjustmentRepository,
    private val users: UserRepository,
    private val wallet: CreditWalletService,
    private val audit: AdminAuditService,
    private val mapper: ObjectMapper,
    private val locks: CreditAdminLocks,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun adjust(adminUserId: Long, request: AdminCreditAdjustmentRequest): AdminCreditAdjustmentResponse {
        val days = request.normalizedDays()
        if (days != null && days !in 1..365) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지급 유효기간은 1~365일이어야 합니다.")
        try {
            return transaction.execute {
                adjustments.findByIdempotencyKey(request.idempotencyKey)?.let { return@execute replay(it, request) }
                val user = locks.target(request.memberId)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.")
                // 잠금 대기 중 같은 요청이 끝났다면 상태 판정 전에 저장된 결과를 돌려준다.
                adjustments.findByIdempotencyKey(request.idempotencyKey)?.let { return@execute replay(it, request) }
                if (user.status == UserStatus.DELETED) throw ResponseStatusException(HttpStatus.CONFLICT, "탈퇴한 회원입니다.")
                // 유니크 삽입이 끝난 요청만 지갑을 변경한다. 아직 완성 전인 행은 같은 트랜잭션 밖에 노출되지 않는다.
                val row = try {
                    adjustments.saveAndFlush(CreditAdminAdjustment(
                        idempotencyKey = request.idempotencyKey, adminUserId = adminUserId, userId = user.id,
                        type = request.type, requestedAmount = request.amount, expiresInDays = days,
                    ))
                } catch (exception: DataIntegrityViolationException) {
                    throw AdjustmentInsertConflict(exception)
                }
                val outcome = wallet.applyAdminAdjustment(user.id, request.amount, days, "admin:${request.idempotencyKey}")
                row.appliedAmount = outcome.appliedAmount
                row.shortfall = request.amount - outcome.appliedAmount
                row.balanceAfter = outcome.balance
                row.expiresAt = outcome.expiresAt
                row.transactionId = outcome.transactionId
                val after = mapper.createObjectNode().put("balance", outcome.balance).put("appliedAmount", outcome.appliedAmount)
                if (days != null) after.put("expiresInDays", days).put("expiresAt", outcome.expiresAt.toString())
                else after.put("shortfall", row.shortfall)
                audit.recordChange(adminUserId,
                    if (days != null) AdminAuditAction.CREDIT_GRANTED else AdminAuditAction.CREDIT_REVOKED,
                    request.memberId.toString(), request.reason,
                    mapper.createObjectNode().put("balance", outcome.beforeBalance), after)
                response(row, request, false)
            }!!
        } catch (conflict: AdjustmentInsertConflict) {
            // 실패한 트랜잭션은 완전히 롤백됐다. 이 조회에는 경쟁에서 이긴 요청의 커밋 결과만 보인다.
            return transaction.execute {
                val stored = adjustments.findByIdempotencyKey(request.idempotencyKey) ?: throw conflict.cause!!
                replay(stored, request)
            }!!
        }
    }

    private fun replay(row: CreditAdminAdjustment, request: AdminCreditAdjustmentRequest): AdminCreditAdjustmentResponse {
        val memberId = users.findById(row.userId).orElseThrow().publicId
        if (memberId != request.memberId || row.type != request.type || row.requestedAmount != request.amount ||
            row.expiresInDays != request.normalizedDays()) {
            throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.IDEMPOTENCY_KEY_CONFLICT,
                "같은 멱등 키에 다른 조정 요청을 사용할 수 없습니다.")
        }
        return response(row, request, true)
    }

    private fun response(row: CreditAdminAdjustment, request: AdminCreditAdjustmentRequest, duplicated: Boolean) =
        AdminCreditAdjustmentResponse(request.memberId, row.type, row.requestedAmount, row.appliedAmount,
            row.shortfall, row.balanceAfter, duplicated, row.expiresAt)

    private class AdjustmentInsertConflict(cause: DataIntegrityViolationException) : RuntimeException(cause)
}
