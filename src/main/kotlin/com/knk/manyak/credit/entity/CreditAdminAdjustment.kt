package com.knk.manyak.credit.entity

import com.knk.manyak.credit.dto.CreditAdjustmentType
import jakarta.persistence.*
import java.time.Instant

/** 멱등 요청의 영구 결과. 0원 회수도 행을 남기며 원장 키로 멱등을 판정하지 않는다. */
@Entity
@Table(name = "credit_admin_adjustments", uniqueConstraints = [UniqueConstraint(
    name = "uq_credit_admin_adjustments_idempotency", columnNames = ["idempotency_key"],
)])
class CreditAdminAdjustment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "idempotency_key", nullable = false, length = 64) val idempotencyKey: String,
    @Column(name = "admin_user_id", nullable = false) val adminUserId: Long,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) val type: CreditAdjustmentType,
    @Column(name = "requested_amount", nullable = false) val requestedAmount: Long,
    @Column(name = "expires_in_days") val expiresInDays: Int?,
    @Column(name = "applied_amount", nullable = false) var appliedAmount: Long = 0,
    @Column(nullable = false) var shortfall: Long = 0,
    @Column(name = "balance_after", nullable = false) var balanceAfter: Long = 0,
    @Column(name = "expires_at") var expiresAt: Instant? = null,
    @Column(name = "transaction_id") var transactionId: Long? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)
