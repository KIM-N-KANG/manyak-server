package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

enum class PlaySessionStatus {
    ACTIVE,
    ENDED,
}

@Entity
@Table(name = "story_play_sessions")
class StoryPlaySession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "start_setting_id")
    val startSettingId: Long? = null,

    @Column(length = 100)
    var title: String? = null,

    @Column(columnDefinition = "TEXT")
    var summary: String? = null,

    @Column(name = "current_turn", nullable = false)
    var currentTurn: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PlaySessionStatus = PlaySessionStatus.ACTIVE,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
