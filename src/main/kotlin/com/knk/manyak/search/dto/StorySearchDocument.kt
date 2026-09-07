package com.knk.manyak.search.dto

import com.knk.manyak.story.dto.StoryAuthorResponse
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.entity.StoryStatus
import java.time.Instant

// OpenSearch 전용 Jackson 2 매퍼가 무인자 생성자와 setter로 읽는다. 영속 엔티티·Spring의 JSON 설정과 분리된 파생 사본이다.
data class StorySearchAuthor(
    var id: Long? = null,
    var nickname: String = "",
)

data class StorySearchDocument(
    var publicId: String = "",
    var title: String = "",
    var oneLineIntro: String = "",
    var genres: List<String> = emptyList(),
    var characterNames: List<String> = emptyList(),
    var thumbnailUrlSm: String? = null,
    var author: StorySearchAuthor? = null,
    var turnCount: Long = 0,
    var likeCount: Long = 0,
    var createdAt: Long = 0,
    var visible: Boolean = false,
) {
    fun toSummary() = StorySummaryResponse(
        id = publicId, thumbnailUrlSm = thumbnailUrlSm, title = title, oneLineIntro = oneLineIntro,
        genres = genres,
        // 기존 카드 계약처럼 내부 PK는 항상 가린다. 검색 인덱스에는 프로필 이미지를 싣지 않는다.
        author = author?.let { StoryAuthorResponse(null, it.nickname, null) },
        turnCount = turnCount, likeCount = likeCount, status = StoryStatus.PUBLISHED,
        createdAt = Instant.ofEpochMilli(createdAt),
    )
}
