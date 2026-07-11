package com.knk.manyak.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 크레딧 적립 로트(스펙 §4-3-7 만료·FIFO, B12). 적립·환불 1건이 로트 1개를 만든다.
 *
 * [remaining]은 FIFO 차감·만료로 감소하는 가변 잔여다(원장 [CreditTransaction]과 달리 상태를 갱신한다).
 * 지갑 [CreditWallet.balance]는 활성(잔여>0) 로트 잔여의 합과 일치하며, 만료는 EXPIRE 원장 행으로 실현한다.
 *
 * [expiresAt]이 NULL이면 무기한(PURCHASE — Phase 3)이고, 보상·환불 로트는 적립 시점 + 30일이다.
 */
@Entity
@Table(name = "credit_lots")
class CreditLot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    // 이 로트를 만든 적립·환불 원장 행. 레거시 승계 로트는 NULL.
    @Column(name = "transaction_id")
    val transactionId: Long? = null,

    @Column(name = "original_amount", nullable = false)
    val originalAmount: Long,

    // FIFO 차감·만료로 감소한다(0 이상, 원금 이하).
    @Column(nullable = false)
    var remaining: Long,

    // NULL = 무기한(PURCHASE). 보상·환불은 적립 시점 + 30일.
    @Column(name = "expires_at")
    val expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
