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
import java.time.Instant
import java.util.UUID

/**
 * 시작 설정. 스토리당 1:N(KNK-515). 외부에는 [publicId](UUID)로만 노출하고 순차 PK는 API에 노출하지 않는다(IDOR 방지).
 * 추천 입력·엔딩은 이 시작 설정에 스코프된다.
 */
@Entity
@Table(name = "story_start_settings")
class StoryStartSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부 노출 식별자. POST /chats의 startSettingId·상세 startSettings[].id가 이 값이다.
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    val story: Story,

    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(columnDefinition = "TEXT")
    var prologue: String? = null,

    @Column(name = "start_situation", columnDefinition = "TEXT")
    var startSituation: String? = null,

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
