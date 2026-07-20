package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 인물↔이미지 매핑(컴파일 산출물 — 스펙 §4-3-9). 간편 제작 컴파일이 1회 확정하고 이후 바뀌지 않는다.
 *
 * 매 턴 요청에 이 매핑만 실어 보내므로 AI는 인물 이미지를 고르지 않고 이름표로만 쓴다.
 * 따라서 같은 인물은 100턴 뒤에도, 재생성해도 같은 이미지다(확률이 아니라 DB 고정).
 *
 * [imageKey]는 nullable이다 — 후보 밖 키 무효화·배정 실패 인물은 이미지 없이 진행한다(graceful).
 * 컴파일이 없는 일반 제작 스토리는 이 행 자체가 생기지 않는다(배경·썸네일만).
 */
@Entity
@Table(name = "story_characters")
class StoryCharacter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "story_id", nullable = false, updatable = false)
    val storyId: Long,

    @Column(nullable = false, updatable = false, length = 50)
    val name: String,

    @Column(name = "image_key", length = 64)
    var imageKey: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
