package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "story_choices",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_choices_order",
            columnNames = ["message_id", "choice_order"],
        ),
    ],
)
class StoryChoice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "play_session_id", nullable = false)
    val playSessionId: Long,

    @Column(name = "message_id", nullable = false)
    val messageId: Long,

    @Column(name = "choice_text", nullable = false, columnDefinition = "TEXT")
    val choiceText: String,

    @Column(name = "choice_order", nullable = false)
    val choiceOrder: Short,

    @Column(name = "is_selected", nullable = false)
    var isSelected: Boolean = false,

    @Column(name = "selected_at")
    var selectedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
