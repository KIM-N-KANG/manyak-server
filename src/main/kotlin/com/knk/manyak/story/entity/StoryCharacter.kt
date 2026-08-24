package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * 스토리 인물(스토리 소유 1:N, 스펙 §4-4 · §5-3-3, KNK-414·KNK-966).
 *
 * 컴파일 응답의 `character_appearances[]`(인물 전원)를 그대로 저장하고, `character_images[]`에서
 * base64를 디코딩해 S3에 올린 URL을 [imageUrl]에 붙인다. 두 배열은 [name]으로 매칭하며, 이미지가 없는
 * 인물은 [imageUrl]이 null이다 — 이미지 실패가 스토리 생성을 막지 않는다(graceful).
 *
 * 외형 7필드는 통글(`character_setting`)에 실리지 않는 별도 데이터로, 썸네일 생성·인물 이미지 재생성 재료다.
 * LLM이 채우지 못한 칸은 null이다.
 */
@Entity
@Table(
    name = "story_characters",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_characters_name",
            columnNames = ["story_id", "name"],
        ),
    ],
)
class StoryCharacter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부에 노출하는 추측 불가능한 식별자(Story와 같은 앱 생성 패턴). 순차 PK는 FK·조인 내부용이다.
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    val story: Story,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(name = "image_url", columnDefinition = "TEXT")
    val imageUrl: String? = null,

    @Column(columnDefinition = "TEXT")
    val gender: String? = null,

    @Column(columnDefinition = "TEXT")
    val age: String? = null,

    @Column(columnDefinition = "TEXT")
    val body: String? = null,

    @Column(columnDefinition = "TEXT")
    val face: String? = null,

    @Column(columnDefinition = "TEXT")
    val hair: String? = null,

    @Column(columnDefinition = "TEXT")
    val outfit: String? = null,

    @Column(name = "visual_identity", columnDefinition = "TEXT")
    val visualIdentity: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
