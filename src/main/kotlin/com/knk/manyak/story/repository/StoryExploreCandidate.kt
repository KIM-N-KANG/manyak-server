package com.knk.manyak.story.repository

import com.knk.manyak.image.service.ImageModerationStatus
import java.util.UUID

/** 탐색 정렬 전에 필요한 식별자와 표지 정보만 읽는다. 본문과 카드 집계는 페이지에 한해 조회한다. */
interface StoryExploreCandidate {
    val publicId: UUID
    val thumbnailImageUrl: String?
    val thumbnailImageKey: String?
    val thumbnailModerationStatus: ImageModerationStatus?
}
