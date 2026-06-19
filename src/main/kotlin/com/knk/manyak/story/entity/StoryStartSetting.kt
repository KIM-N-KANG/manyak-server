package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "story_start_settings")
class StoryStartSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false, unique = true)
    val story: Story,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(columnDefinition = "TEXT")
    val prologue: String? = null,

    @Column(name = "start_situation", columnDefinition = "TEXT")
    val startSituation: String? = null,

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
