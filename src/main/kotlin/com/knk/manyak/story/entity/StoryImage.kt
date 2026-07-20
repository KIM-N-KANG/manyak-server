package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 스토리↔배경 후보 연결(스펙 §4-3-9). 등록 시 장르 매칭으로 확정하고 매 턴 같은 목록을 AI 요청에 싣는다.
 *
 * 의미 태그는 복제하지 않는다 — 정본은 `image_presets` 한 곳이고 여기는 얇은 연결이다.
 */
@Entity
@Table(name = "story_images")
class StoryImage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "story_id", nullable = false, updatable = false)
    val storyId: Long,

    @Column(name = "image_key", nullable = false, updatable = false, length = 64)
    val imageKey: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
