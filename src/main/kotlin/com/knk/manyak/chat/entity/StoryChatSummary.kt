package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "story_chat_summaries")
class StoryChatSummary(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "chat_id", nullable = false, updatable = false)
    val chatId: Long,
    @Column(name = "through_turn", nullable = false, updatable = false)
    val throughTurn: Int,
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    val summary: String,
    @Column(name = "invalidated_at")
    var invalidatedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
