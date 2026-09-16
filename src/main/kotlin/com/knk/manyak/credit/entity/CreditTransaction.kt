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

/** 크레딧 증감 사유. 적립·환불은 양수 amount, 소모·만료는 음수 amount로 기록한다(balance = SUM(amount)). */
enum class CreditReason {
    SIGNUP_REWARD,
    INVITE_REWARD,
    ATTENDANCE_REWARD,
    STORY_CREATION,
    CHAT_TURN,
    CHAT_IMAGE,
    REFUND,
    PURCHASE,
    PURCHASE_REVERSAL,

    // 보상·환불 로트가 30일 유효기간을 넘겨 만료된 잔여를 회수하는 음수 행(스펙 §4-3-7 만료, B12).
    EXPIRE,
}

/**
 * 크레딧 원장 행(append-only). 생성 후 수정하지 않는다 — 환불도 수정이 아니라 REFUND 행 추가다(스펙 §4-3-7).
 *
 * [idempotencyKey]는 적립·요청 내 환불의 중복을 막는 유니크 제약이다. 소모·대사 환불의 NULL은 서로 충돌하지 않는다.
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

    // 소모·환불은 연관 리소스를 참조한다. 채팅 이미지는 CHAT_IMAGE/채팅 PK로 CHAT 대사 그룹과 분리한다.
    @Column(name = "ref_type", length = 30)
    val refType: String? = null,

    @Column(name = "ref_id")
    val refId: Long? = null,

    // 보상·환불 멱등 키(signup:{userId}, refund:charge:{차감행ID} 등). 소모·대사 환불은 NULL.
    @Column(name = "idempotency_key")
    val idempotencyKey: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
