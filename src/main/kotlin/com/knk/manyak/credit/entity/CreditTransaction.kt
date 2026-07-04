package com.knk.manyak.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** 크레딧 증감 사유. 적립·환불은 양수 amount, 소모는 음수 amount로 기록한다(balance = SUM(amount)). */
enum class CreditReason {
    SIGNUP_REWARD,
    INVITE_REWARD,
    ATTENDANCE_REWARD,
    STORY_CREATION,
    CHAT_TURN,
    REFUND,
    PURCHASE,
}

/**
 * 크레딧 원장 행(append-only). 생성 후 수정하지 않는다 — 환불도 수정이 아니라 REFUND 행 추가다(스펙 §4-3-7).
 *
 * [idempotencyKey]는 보상 중복을 막는 유니크 제약이다(소모/환불 행은 NULL). NULL은 서로 다른 값으로 취급돼 충돌하지 않는다.
 */
@Entity
@Table(
    name = "credit_transactions",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_credit_transactions_idempotency", columnNames = ["idempotency_key"]),
    ],
)
class CreditTransaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    // 부호 있는 증감. 적립·환불 양수, 소모 음수.
    @Column(nullable = false)
    val amount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val reason: CreditReason,

    // 소모 행은 연관 리소스(STORY·CHAT·AI_CALL_LOG), REFUND 행은 소모 행(CREDIT_TRANSACTION)을 가리킨다.
    @Column(name = "ref_type", length = 30)
    val refType: String? = null,

    @Column(name = "ref_id")
    val refId: Long? = null,

    // 보상 멱등 키(signup:{userId} 등). 소모/환불 행은 NULL.
    @Column(name = "idempotency_key")
    val idempotencyKey: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
