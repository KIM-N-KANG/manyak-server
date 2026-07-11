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
 * 주요 사건(스토리 소유 1:N, 스펙 §4-3-10). 스토리당 최대 10개(앱에서 강제).
 *
 * 채팅 런타임이 목표 사건 선정·진행·완결 판정 입력으로 쓰는 저장 초안 구조다. 이 티켓(KNK-418)은 스키마와
 * 저작(조회·교체 저장) API까지이며, 런타임 판정 연동은 별도 범위다.
 */
@Entity
@Table(
    name = "story_main_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_main_events_order",
            columnNames = ["story_id", "sort_order"],
        ),
    ],
)
class StoryMainEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    val story: Story,

    // AI 요청·거쳐온 사건 기록의 식별자로 쓰는 사건 이름.
    @Column(nullable = false, length = 100)
    val name: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val description: String,

    // 목표 사건 선정·완결 판정의 관련성 근거 문장(런타임 의미는 §4-3-10).
    @Column(name = "key_sentence", nullable = false, columnDefinition = "TEXT")
    val keySentence: String,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Short,

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
