package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryStatus
import com.knk.manyak.story.entity.StoryVisibility
import io.swagger.v3.oas.annotations.media.Schema

// visibility에 기본값을 두지 않는다: 필드가 누락되면(`{}`) 역직렬화가 실패해 400이 나야 한다.
// 잘못된 enum 값도 역직렬화 단계에서 400으로 걸린다.

@Schema(description = "초안 발행 요청. 발행 시 공개 범위를 함께 정한다.")
data class PublishStoryRequest(
    @field:Schema(description = "발행 후 공개 범위(PUBLIC/PRIVATE)", example = "PRIVATE")
    val visibility: StoryVisibility,
)

@Schema(description = "공개 범위 변경 요청")
data class UpdateStoryVisibilityRequest(
    @field:Schema(description = "변경할 공개 범위(PUBLIC/PRIVATE)", example = "PUBLIC")
    val visibility: StoryVisibility,
)

@Schema(description = "스토리 발행·공개 상태 응답")
data class StoryPublicationResponse(
    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "등록 상태", example = "PUBLISHED")
    val status: StoryStatus,

    @field:Schema(description = "공개 범위", example = "PUBLIC")
    val visibility: StoryVisibility,
)
