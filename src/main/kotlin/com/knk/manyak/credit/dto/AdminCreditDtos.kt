package com.knk.manyak.credit.dto

import jakarta.validation.constraints.*
import java.time.Instant
import java.util.UUID

enum class CreditAdjustmentType { GRANT, REVOKE }

data class AdminCreditAdjustmentRequest(
    val memberId: UUID,
    val type: CreditAdjustmentType,
    @field:Min(1) @field:Max(100000) val amount: Long,
    val expiresInDays: Int? = null,
    @field:NotBlank @field:Size(max = 64) @field:Pattern(regexp = "[A-Za-z0-9-]+") val idempotencyKey: String,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    // 회수 요청의 유효기간은 비교와 저장에서도 무시한다.
    fun normalizedDays(): Int? = if (type == CreditAdjustmentType.GRANT) expiresInDays ?: 30 else null
}

data class AdminCreditAdjustmentResponse(
    val memberId: UUID,
    val type: CreditAdjustmentType,
    val requestedAmount: Long,
    val appliedAmount: Long,
    val shortfall: Long,
    val balance: Long,
    val duplicated: Boolean,
    val expiresAt: Instant?,
)

data class AdminCreditPolicyRequest(
    val amount: Long,
    val effectiveUntil: Instant? = null,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
data class AdminCreditPolicyDeleteRequest(@field:NotBlank @field:Size(max = 500) val reason: String)
data class AdminCreditPolicyOverrideResponse(val amount: Long, val effectiveUntil: Instant?, val updatedAt: Instant)
data class AdminCreditPolicyResponse(
    val policyKey: String,
    val defaultAmount: Long,
    val effectiveAmount: Long,
    val minimum: Long,
    val override: AdminCreditPolicyOverrideResponse?,
)
data class AdminCreditPoliciesResponse(val items: List<AdminCreditPolicyResponse>)
