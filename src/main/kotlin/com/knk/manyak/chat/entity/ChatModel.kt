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
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() { updatedAt = Instant.now() }
}
