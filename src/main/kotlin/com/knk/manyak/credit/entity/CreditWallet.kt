package com.knk.manyak.credit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 회원 크레딧 지갑(회원당 1개). [balance]는 원장([CreditTransaction]) 합계의 캐시다(스펙 §4-3-7).
 *
 * 차감은 지갑 행 비관적 락으로 직렬화하므로, balance 갱신은 락을 쥔 트랜잭션에서만 일어난다.
 */
@Entity
@Table(
    name = "credit_wallets",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_credit_wallets_user", columnNames = ["user_id"]),
    ],
)
class CreditWallet(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false)
    var balance: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
