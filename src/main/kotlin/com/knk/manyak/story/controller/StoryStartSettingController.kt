package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.UpdateStartSettingRequest
import com.knk.manyak.story.service.StoryStartSettingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Stories", description = "스토리 API")
@RestController
@RequestMapping("/api/v1/stories/{storyId}/start-setting")
class StoryStartSettingController(
    private val storyStartSettingService: StoryStartSettingService,
) {

    @Operation(
        summary = "시작설정 조회",
        description = "스토리의 시작설정을 조회합니다. 초안은 비공개라 인증 필수·소유자만 조회할 수 있습니다(무토큰 401, 타인 403). " +
            "아직 저장 전이면 name은 빈 문자열, 나머지는 null입니다. 스토리가 없으면 404입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = StoryStartSettingResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "스토리 소유자가 아님", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @GetMapping
    fun getStartSetting(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ): StoryStartSettingResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return storyStartSettingService.getStartSetting(storyId, ownerId)
    }

    @Operation(
        summary = "시작설정 저작 저장",
        description = "시작설정을 저장합니다(PATCH 의미: 보낸 필드만 갱신, 미제공 필드는 유지). 처음 저장이면 생성합니다. " +
            "인증 필수·소유자만 저장할 수 있습니다(무토큰 401, 타인 403). 스토리가 없으면 404입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "저장 성공",
                content = [Content(schema = Schema(implementation = StoryStartSettingResponse::class))],
            ),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "스토리 소유자가 아님", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @PutMapping
    fun upsertStartSetting(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: UpdateStartSettingRequest,
    ): StoryStartSettingResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return storyStartSettingService.upsertStartSetting(storyId, ownerId, request)
    }
}
