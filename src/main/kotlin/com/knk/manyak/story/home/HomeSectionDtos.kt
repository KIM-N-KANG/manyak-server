package com.knk.manyak.story.home

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.story.dto.StorySummaryResponse
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.node.ObjectNode

// 원본 트리를 함께 보존해 생략과 명시적 null, 잘못된 타입을 기존 필드별 검증에서 구분한다.
@Schema(description = "홈 섹션 등록 요청")
data class HomeSectionCreateRequest(
    @get:Schema(description = "이름. 앞뒤 공백 제거 후 1~30자", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 30)
    val name: String,
    @get:Schema(description = "스토리 출처", requiredMode = Schema.RequiredMode.REQUIRED)
    val source: HomeSectionSource,
    @get:Schema(description = "MANUAL 필수. 스토리 공개 UUID 1~20개, 지정 순서 유지", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val storyIds: List<String>?,
    @get:Schema(description = "FILTER 전용. 활성 제공 장르 정식 이름 최대 10개. 빈 배열은 조건 제거", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val genres: List<String>?,
    @get:Schema(description = "FILTER 전용. 기본값 false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val originalOnly: Boolean?,
    @get:Schema(description = "FILTER 필수. 공개 목록 정렬", requiredMode = Schema.RequiredMode.NOT_REQUIRED, allowableValues = ["popular", "latest", "likes", "chats"])
    val sort: String?,
    @get:Schema(description = "카드 상한. 기본값 20", requiredMode = Schema.RequiredMode.NOT_REQUIRED, minimum = "1", maximum = "20")
    val itemLimit: Int?,
    @get:Schema(description = "활성 여부. 기본값 true", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @get:JsonProperty("isActive") val isActive: Boolean?,
    @get:Schema(description = "노출 순서. 등록 생략 시 현재 최댓값 + 1", requiredMode = Schema.RequiredMode.NOT_REQUIRED, minimum = "1")
    val sortOrder: Int?,
    @get:Schema(description = "변경 사유 1~500자", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
    val reason: String,
    @get:JsonIgnore @get:Schema(hidden = true) val raw: ObjectNode,
) {
    companion object {
        @JvmStatic @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(raw: ObjectNode) = HomeSectionCreateRequest(
            name = raw["name"]?.takeIf { it.isString }?.asText() ?: "",
            source = HomeSectionSource.entries.find { it.name == raw["source"]?.asText() } ?: HomeSectionSource.MANUAL,
            storyIds = raw["storyIds"]?.takeIf { it.isArray }?.toList()?.map { it.asText() },
            genres = raw["genres"]?.takeIf { it.isArray }?.toList()?.map { it.asText() },
            originalOnly = raw["originalOnly"]?.takeIf { it.isBoolean }?.asBoolean(),
            sort = raw["sort"]?.takeIf { it.isString }?.asText(),
            itemLimit = raw["itemLimit"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt(),
            isActive = raw["isActive"]?.takeIf { it.isBoolean }?.asBoolean(),
            sortOrder = raw["sortOrder"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt(),
            reason = raw["reason"]?.takeIf { it.isString }?.asText() ?: "",
            raw = raw,
        )
    }
}

@Schema(description = "홈 섹션 부분 수정. 보낸 필드만 반영 요청")
data class HomeSectionUpdateRequest(
    @get:Schema(description = "이름. 앞뒤 공백 제거 후 1~30자", requiredMode = Schema.RequiredMode.NOT_REQUIRED, minLength = 1, maxLength = 30)
    val name: String?,
    @get:Schema(description = "스토리 출처", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val source: HomeSectionSource?,
    @get:Schema(description = "MANUAL 필수. 스토리 공개 UUID 1~20개, 지정 순서 유지", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val storyIds: List<String>?,
    @get:Schema(description = "FILTER 전용. 활성 제공 장르 정식 이름 최대 10개. 빈 배열은 조건 제거", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val genres: List<String>?,
    @get:Schema(description = "FILTER 전용. 기본값 false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val originalOnly: Boolean?,
    @get:Schema(description = "FILTER 필수. 공개 목록 정렬", requiredMode = Schema.RequiredMode.NOT_REQUIRED, allowableValues = ["popular", "latest", "likes", "chats"])
    val sort: String?,
    @get:Schema(description = "카드 상한. 기본값 20", requiredMode = Schema.RequiredMode.NOT_REQUIRED, minimum = "1", maximum = "20")
    val itemLimit: Int?,
    @get:Schema(description = "활성 여부. 기본값 true", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @get:JsonProperty("isActive") val isActive: Boolean?,
    @get:Schema(description = "노출 순서. 등록 생략 시 현재 최댓값 + 1", requiredMode = Schema.RequiredMode.NOT_REQUIRED, minimum = "1")
    val sortOrder: Int?,
    @get:Schema(description = "변경 사유 1~500자", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
    val reason: String,
    @get:JsonIgnore @get:Schema(hidden = true) val raw: ObjectNode,
) {
    companion object {
        @JvmStatic @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(raw: ObjectNode) = HomeSectionUpdateRequest(
            name = raw["name"]?.takeIf { it.isString }?.asText(),
            source = HomeSectionSource.entries.find { it.name == raw["source"]?.asText() },
            storyIds = raw["storyIds"]?.takeIf { it.isArray }?.toList()?.map { it.asText() },
            genres = raw["genres"]?.takeIf { it.isArray }?.toList()?.map { it.asText() },
            originalOnly = raw["originalOnly"]?.takeIf { it.isBoolean }?.asBoolean(),
            sort = raw["sort"]?.takeIf { it.isString }?.asText(),
            itemLimit = raw["itemLimit"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt(),
            isActive = raw["isActive"]?.takeIf { it.isBoolean }?.asBoolean(),
            sortOrder = raw["sortOrder"]?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt(),
            reason = raw["reason"]?.takeIf { it.isString }?.asText() ?: "",
            raw = raw,
        )
    }
}

@Schema(description = "홈 섹션 삭제 요청")
data class HomeSectionDeleteRequest(
    @get:Schema(description = "변경 사유 1~500자", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
    val reason: String,
    @get:JsonIgnore @get:Schema(hidden = true) val raw: ObjectNode,
) {
    companion object {
        @JvmStatic @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(raw: ObjectNode) = HomeSectionDeleteRequest(
            reason = raw["reason"]?.takeIf { it.isString }?.asText() ?: "",
            raw = raw,
        )
    }
}

@Schema(description = "관리자 홈 섹션 목록")
data class HomeSectionListResponse(val items: List<HomeSectionResponse>)

@Schema(description = "관리자 홈 섹션")
data class HomeSectionResponse(
    @get:Schema(format = "uuid") val id: String,
    val name: String,
    val source: HomeSectionSource,
    val stories: List<HomeSectionStoryResponse>,
    val genres: List<String>,
    val originalOnly: Boolean,
    @get:Schema(nullable = true, allowableValues = ["popular", "latest", "likes", "chats"], requiredMode = Schema.RequiredMode.REQUIRED)
    val sort: String?,
    @get:Schema(minimum = "1", maximum = "20") val itemLimit: Int,
    @get:JsonProperty("isActive") val isActive: Boolean,
    @get:Schema(minimum = "1") val sortOrder: Int,
    @get:Schema(format = "date-time") val createdAt: String,
    @get:Schema(format = "date-time") val updatedAt: String,
)

@Schema(description = "수동 지정 스토리의 현재 상태")
data class HomeSectionStoryResponse(
    @get:Schema(format = "uuid") val storyId: String,
    @get:Schema(description = "토큰 원문 제목") val title: String,
    @get:JsonProperty("isListed") val isListed: Boolean,
)

@Schema(description = "공개 홈 목록")
data class StoryHomeResponse(val sections: List<StoryHomeSectionResponse>)

@Schema(description = "공개 홈 섹션")
data class StoryHomeSectionResponse(
    @get:Schema(description = "기본 섹션이면 null", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid")
    val id: String?,
    val name: String,
    @get:ArraySchema(maxItems = 20) val items: List<StorySummaryResponse>,
)
