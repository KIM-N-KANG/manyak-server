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

    // 일반 모드 초안은 탭별로 부분 저장하므로 빈 값으로 시작할 수 있다(KNK-460). NOT NULL 컬럼이라 빈 문자열 기본값을 둔다.
    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(columnDefinition = "TEXT")
    var prologue: String? = null,

    @Column(name = "start_situation", columnDefinition = "TEXT")
    var startSituation: String? = null,

    @Column(name = "opening_scene", columnDefinition = "TEXT")
    var openingScene: String? = null,

    @Column(name = "first_ai_message", columnDefinition = "TEXT")
    var firstAiMessage: String? = null,

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
