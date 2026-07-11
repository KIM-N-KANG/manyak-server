package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryMainEvent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 주요 사건 개수 상한(스펙 §4-3-10, 스토리당 최대 10). */
const val MAX_MAIN_EVENTS = 10

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
