package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 스토리 엔딩(시작 설정 소유 1:N, KNK-419). 도달 조건은 자유 텍스트로 저장만 하며 발동 로직은 이번 범위 밖이다.
 */
@Entity
@Table(
    name = "story_endings",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_endings_order",
            columnNames = ["start_setting_id", "sort_order"],
        ),
    ],
)
class StoryEnding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "start_setting_id", nullable = false)
    val startSetting: StoryStartSetting,

    @Column(nullable = false, length = 100)
    val title: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "condition_text", columnDefinition = "TEXT")
    val conditionText: String? = null,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Short,

    @Column(nullable = false)
    val enabled: Boolean = true,

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
