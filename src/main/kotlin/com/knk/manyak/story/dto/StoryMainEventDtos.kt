package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryMainEvent
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/** 주요 사건 개수 상한(스펙 §4-3-10, 스토리당 최대 10). */
const val MAX_MAIN_EVENTS = 10

@Schema(description = "주요 사건 교체 저장 요청. 보낸 목록으로 스토리의 주요 사건 전체를 대체한다.")
data class ReplaceMainEventsRequest(
    // 기본값을 두지 않는다: 필드가 누락되면(`{}`·오타) 역직렬화가 실패해 400이 나야 한다. 기본값(emptyList)을
    // 두면 누락 요청이 명시적 빈 배열과 구분되지 않아 스토리의 주요 사건을 조용히 전부 지운다(silent wipe 방지).
    // 전부 삭제는 명시적 `"mainEvents": []` 로만 허용한다.
    @field:Valid
    @field:Size(max = MAX_MAIN_EVENTS, message = "주요 사건은 최대 ${MAX_MAIN_EVENTS}개까지 등록할 수 있습니다.")
    @field:ArraySchema(
        schema = Schema(implementation = MainEventItem::class),
        arraySchema = Schema(description = "주요 사건 목록(최대 10). 빈 배열이면 전부 삭제된다. 배열 순서가 표시 순서가 된다."),
    )
    // 원소 @NotNull: `[null]` 같은 본문은 null 원소가 담긴 리스트로 역직렬화되는데, @Valid는 non-null 항목에만
    // 캐스케이드하므로 null이 그대로 replaceMainEvents까지 내려가 mapIndexed에서 500이 된다. 원소 non-null 제약으로
    // 검증 단계에서 400으로 거른다.
    val mainEvents: List<@NotNull MainEventItem>,
)

@Schema(description = "주요 사건 입력 항목")
data class MainEventItem(
    @field:NotBlank(message = "주요 사건 이름은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "주요 사건 이름은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "사건 이름(AI 요청·거쳐온 사건 기록의 식별자)", example = "잃어버린 편지를 발견한다")
    val name: String,

    @field:NotBlank(message = "주요 사건 설명은 비어 있을 수 없습니다.")
    @field:Schema(description = "사건 설명", example = "주인공이 다락방에서 어머니가 남긴 봉인된 편지를 찾아낸다.")
    val description: String,

    @field:NotBlank(message = "주요 사건 핵심 문장은 비어 있을 수 없습니다.")
    @field:Schema(description = "목표 사건 선정·완결 판정의 관련성 근거 문장", example = "봉인된 편지를 열어 어머니의 비밀을 마주한다.")
    val keySentence: String,
)

@Schema(description = "주요 사건 응답 항목")
data class StoryMainEventResponse(
    @field:Schema(description = "사건 이름", example = "잃어버린 편지를 발견한다")
    val name: String,

    @field:Schema(description = "사건 설명", example = "주인공이 다락방에서 어머니가 남긴 봉인된 편지를 찾아낸다.")
    val description: String,

    @field:Schema(description = "핵심 문장", example = "봉인된 편지를 열어 어머니의 비밀을 마주한다.")
    val keySentence: String,

    @field:Schema(description = "표시 순서(0부터).", example = "0")
    val sortOrder: Int,
)

fun StoryMainEvent.toMainEventResponse(): StoryMainEventResponse =
    StoryMainEventResponse(
        name = name,
        description = description,
        keySentence = keySentence,
        sortOrder = sortOrder.toInt(),
    )
