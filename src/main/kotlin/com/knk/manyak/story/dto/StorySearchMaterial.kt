package com.knk.manyak.story.dto

import com.knk.manyak.image.service.ImageModerationStatus
import java.time.Instant
import java.util.UUID

/** 검색 문서를 만드는 데 필요한 스토리 원문 값(STORY 4-1-27). 제목과 소개는 이름 토큰을 치환하지 않은 저장값이다. */
data class StorySearchMaterial(
    val id: Long,
    val publicId: UUID,
    val userId: Long?,
    val title: String,
    val oneLineIntro: String?,
    val protagonistName: String?,
    val genre: String?,
    val thumbnailImageUrl: String?,
    val thumbnailImageKey: String?,
    val thumbnailModerationStatus: ImageModerationStatus,
    val createdAt: Instant,
    val publiclyListed: Boolean,
    // 스토리별 저장(id) 순서.
    val characterNames: List<String>,
    val likeCount: Long,
)
