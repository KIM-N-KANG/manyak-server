package com.knk.manyak.story.dto

import com.knk.manyak.story.entity.StoryStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "일반 모드 초안 생성 요청. 모든 필드가 선택이며, 빈 본문이면 빈 초안을 만든다.")
data class CreateStoryDraftRequest(
    @field:Size(max = 100, message = "제목은 100자를 넘을 수 없습니다.")
    @field:Schema(description = "스토리 제목(선택). 없으면 빈 제목으로 시작한다.", example = "잿빛 왕관", nullable = true)
    val title: String? = null,

    @field:Size(max = 255, message = "한 줄 소개는 255자를 넘을 수 없습니다.")
    @field:Schema(description = "한 줄 소개(선택)", nullable = true)
    val oneLineIntro: String? = null,

    @field:Schema(description = "설명(선택)", nullable = true)
    val description: String? = null,

    @field:Size(max = 255, message = "장르는 255자를 넘을 수 없습니다.")
    @field:Schema(description = "장르(선택)", nullable = true)
    val genre: String? = null,
)

@Schema(description = "초안 생성 응답")
data class StoryDraftResponse(
    @field:Schema(description = "스토리 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "등록 상태", example = "DRAFT")
    val status: StoryStatus,
)

@Schema(description = "세계관 초안 저장 요청(PATCH 의미: 보낸 필드만 갱신, 미제공 필드는 유지).")
data class UpdateStorySettingRequest(
    @field:Schema(description = "세계관 설정(선택). 미제공 시 기존 값 유지.", example = "몰락한 왕국 아르덴", nullable = true)
    val worldSetting: String? = null,

    @field:Schema(description = "인물 설정(선택). 미제공 시 기존 값 유지.", nullable = true)
    val characterSetting: String? = null,

    @field:Schema(description = "사용자 역할 설정(선택)", nullable = true)
    val userRoleSetting: String? = null,

    @field:Schema(description = "규칙 설정(선택)", nullable = true)
    val ruleSetting: String? = null,
)

@Schema(description = "세계관 설정 응답. 아직 저장 전인 필드는 null이다.")
data class StorySettingResponse(
    @field:Schema(description = "세계관 설정", nullable = true)
    val worldSetting: String?,

    @field:Schema(description = "인물 설정", nullable = true)
    val characterSetting: String?,

    @field:Schema(description = "사용자 역할 설정", nullable = true)
    val userRoleSetting: String?,

    @field:Schema(description = "규칙 설정", nullable = true)
    val ruleSetting: String?,
)
