package com.knk.manyak.story.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "시작설정 저작 요청(PATCH 의미: 보낸 필드만 갱신, 미제공 필드는 유지). AI 없이 사용자가 직접 저장한다.")
data class UpdateStartSettingRequest(
    @field:Size(max = 100, message = "시작 장면 이름은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "시작 장면 이름(선택)", example = "선왕의 장례식 날", nullable = true)
    val name: String? = null,

    @field:Schema(description = "도입부 내레이션(선택)", nullable = true)
    val prologue: String? = null,

    @field:Schema(description = "시작 상황(선택)", nullable = true)
    val startSituation: String? = null,

    @field:Schema(description = "오프닝 장면(선택)", nullable = true)
    val openingScene: String? = null,

    @field:Schema(description = "첫 AI 메시지(선택)", nullable = true)
    val firstAiMessage: String? = null,
)
