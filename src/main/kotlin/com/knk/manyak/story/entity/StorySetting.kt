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
@Table(name = "story_settings")
class StorySetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false, unique = true)
    val story: Story,

    @Column(name = "world_setting", nullable = false, columnDefinition = "TEXT")
    val worldSetting: String,

    @Column(name = "character_setting", nullable = false, columnDefinition = "TEXT")
    val characterSetting: String,

    @Column(name = "user_role_setting", columnDefinition = "TEXT")
    val userRoleSetting: String? = null,

    @Column(name = "rule_setting", columnDefinition = "TEXT")
    val ruleSetting: String? = null,

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
