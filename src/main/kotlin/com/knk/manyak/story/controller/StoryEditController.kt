package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.StoryEditFormResponse
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.submission.StorySubmissionService
import com.knk.manyak.story.submission.SubmissionAccepted
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
    private val submissions: StorySubmissionService,
) {

    @Operation(
        summary = "스토리 수정 폼 조회",
        description = "미승인 제출본을 반영한 편집 가능 필드와 submission 메타를 조회합니다. 인증은 선택이며, 회원 소유 " +
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
    ): JsonNode = submissions.editForm(storyId, userId)

    @Operation(summary = "스토리 수정 검수 제출", description = "회원 소유자만 허용합니다. visibility 단독은 즉시 200, 그 외 입력은 202로 접수하며 승인 후 반영합니다. PENDING 중에는 모든 PATCH가 409입니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "공개 범위 즉시 반영", content = [Content(schema = Schema(implementation = StoryEditFormResponse::class))]),
        ApiResponse(responseCode = "202", description = "검수 접수", content = [Content(schema = Schema(implementation = SubmissionAccepted::class))]),
        ApiResponse(responseCode = "400", description = "입력 검증 실패"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "스토리 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "스토리 없음"),
        ApiResponse(responseCode = "409", description = "검수 중 또는 이미지 이름 중복"),
    ])
    @PatchMapping("/{storyId}")
    fun updateStory(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
        @RequestBody request: UpdateStoryRequest,
    ): ResponseEntity<Any> {
        val result = submissions.update(storyId, request, userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED))
        return ResponseEntity.status(if (result is SubmissionAccepted) 202 else 200).body(result)
    }
}
