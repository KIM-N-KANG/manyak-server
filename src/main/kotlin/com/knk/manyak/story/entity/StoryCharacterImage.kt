package com.knk.manyak.story.entity

import com.knk.manyak.image.service.ImageModerationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * 인물 이미지(인물 소유 1:N, 스펙 §4-3-8 스토리 이미지 업로드, KNK-1126, V76).
 *
 * 컴파일이 만든 한 장(`story_characters.image_url`)을 대체하는 정본이다. 소유자가 표정·상황별로 여러 장을
 * 올리며, [imageName]은 `{인물이름}_{접미}` 형식이고 같은 인물 안에서 유일하다.
 *
 * 삭제는 이 행만 지우고 **S3 객체는 남긴다** — 지난 채팅의 `[[URL]]` 마커가 그 객체를 가리키고 있어 지우면
 * 옛 대화가 깨진다(스펙 결정 기록).
 */
@Entity
@Table(
    name = "story_character_images",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_story_character_images_name", columnNames = ["character_id", "image_name"]),
    ],
    indexes = [Index(name = "idx_story_character_images_character", columnList = "character_id")],
)
class StoryCharacterImage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부 노출 식별자. 삭제 API가 이 값을 받는다(순차 PK 비노출).
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    val character: StoryCharacter,

    @Column(name = "image_name", nullable = false, length = 120)
    val imageName: String,

    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    val imageUrl: String,

    // 표시 순서(등록 순서, 0부터). 재정렬 API는 아직 없다.
    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,

    // 검수 상태(스펙 §4-3-8 검수 게이트). APPROVED만 상세·채팅 요청에 나간다. 소유자의 편집 폼은 상태와
    // 함께 전부 본다. 지금은 기본값이 APPROVED라 즉시 반영이다.
    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 20)
    val moderationStatus: ImageModerationStatus = ImageModerationStatus.APPROVED,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        /** 컴파일이 만든 첫 장과 V76 백필이 쓰는 접미. 상세 응답의 대표 이미지 판정 기준이다. */
        const val DEFAULT_SUFFIX = "기본"

        /** 인물당 상한(스펙 §4-3-8). 넘으면 400이다. */
        const val MAX_IMAGES_PER_CHARACTER = 10

        fun defaultImageNameOf(characterName: String): String = "${characterName}_$DEFAULT_SUFFIX"
    }
}
