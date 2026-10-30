package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "chat_models")
class ChatModel(
    @Id @Column(length = 20) val code: String,
    @Column(nullable = false, length = 30) var name: String,
    @Column(name = "turn_cost", nullable = false) var turnCost: Long,
    @Column(name = "sort_order", nullable = false) var sortOrder: Int,
    @Column(name = "is_active", nullable = false) var isActive: Boolean = true,
    @Column(name = "is_default", nullable = false) var isDefault: Boolean = false,
    @Column(name = "promo_turn_cost") var promoTurnCost: Long? = null,
    @Column(name = "promo_starts_at") var promoStartsAt: Instant? = null,
    @Column(name = "promo_ends_at") var promoEndsAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
) {
    fun promotionActiveAt(now: Instant): Boolean =
        promoTurnCost != null && promoStartsAt?.let { !now.isBefore(it) } == true &&
            promoEndsAt?.let { now.isBefore(it) } == true

    fun appliedTurnCost(now: Instant): Long = if (promotionActiveAt(now)) promoTurnCost!! else turnCost

    @PreUpdate
    fun updateTimestamp() { updatedAt = Instant.now() }
}
