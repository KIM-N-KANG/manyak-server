package com.knk.manyak.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class CreditOrderProvider { GROBLE, GOOGLE_PLAY }
enum class CreditOrderStatus { PENDING, COMPLETED, REFUNDED }

/** 주문 생성 시 가격·총량을 고정한다. 설정이 바뀌어도 결제 대기 중인 주문의 금액은 바꾸지 않는다. */
@Entity
@Table(name = "credit_orders", indexes = [Index(name = "idx_credit_orders_user_created_at", columnList = "user_id, created_at DESC")])
class CreditOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "product_id", nullable = false, length = 32)
    val productId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: CreditOrderProvider,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CreditOrderStatus = CreditOrderStatus.PENDING,

    @Column(name = "price_krw", nullable = false)
    val priceKrw: Long,

    @Column(name = "credit_amount", nullable = false)
    val creditAmount: Long,

    @Column(name = "provider_ref", unique = true)
    var providerRef: String? = null,

    @Column(name = "credit_transaction_id")
    var creditTransactionId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "reversal_shortfall")
    var reversalShortfall: Long? = null,

    @Column(name = "refunded_at")
    var refundedAt: Instant? = null,
)
