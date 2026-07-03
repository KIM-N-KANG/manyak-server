package com.knk.manyak.chat.entity

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

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

@Entity
@Table(
    name = "story_messages",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_messages_order",
            columnNames = ["chat_id", "message_order"],
        ),
    ],
)
class StoryMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val role: MessageRole,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "message_order", nullable = false)
    val messageOrder: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
