package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.StoryEditFormResponse
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.service.StoryEditService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Stories", description = "스토리 API")
@Validated
@RestController
@RequestMapping("/api/v1/stories")
class StoryEditController(
    private val storyEditService: StoryEditService,
) {

    @Operation(
        summary = "스토리 수정 폼 조회",
        description = "수정 폼을 채우기 위한 편집 가능 필드 전체(통글 4필드 포함)를 조회합니다. 인증은 선택이며, 회원 소유 " +
            "스토리는 소유자만(타인·미인증 403), 소유자 없는 게스트 스토리는 허용합니다. 없는 스토리는 404입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공", content = [Content(schema = Schema(implementation = StoryEditFormResponse::class))]),
            ApiResponse(responseCode = "403", description = "스토리 소유자가 아님", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @GetMapping("/{storyId}/edit")
    fun getEditForm(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ): StoryEditFormResponse = storyEditService.getEditForm(storyId, userId)

    @Operation(
        summary = "스토리 수정(부분 갱신)",
        description = "보낸 필드만 교체하고 나머지는 유지합니다(간편·일반 제작 무관). 리스트는 보내면 전체 교체, 빈 배열이면 " +
            "전부 삭제입니다. 인증은 선택이며 회원 소유 스토리는 소유자만(타인·미인증 403). 검증 실패 400, 없는 스토리 404.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "수정 성공", content = [Content(schema = Schema(implementation = StoryEditFormResponse::class))]),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "스토리 소유자가 아님", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @PatchMapping("/{storyId}")
    fun updateStory(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: UpdateStoryRequest,
    ): StoryEditFormResponse = storyEditService.updateStory(storyId, userId, request)
}
