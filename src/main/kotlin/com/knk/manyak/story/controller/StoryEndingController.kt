package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.ReplaceEndingsRequest
import com.knk.manyak.story.dto.StoryEndingResponse
import com.knk.manyak.story.service.StoryEndingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
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
@RequestMapping("/api/v1/stories/{storyId}/endings")
class StoryEndingController(
    private val storyEndingService: StoryEndingService,
) {

    @Operation(
        summary = "엔딩 목록 조회",
        description = "스토리의 엔딩을 표시 순서로 조회합니다. 시작 설정·엔딩이 없으면 빈 배열입니다. 스토리가 없으면 404입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = StoryEndingResponse::class)))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping
    fun getEndings(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ): List<StoryEndingResponse> = storyEndingService.getEndings(storyId, userId)

    @Operation(
        summary = "엔딩 교체 저장",
        description = "보낸 목록으로 스토리(시작 설정)의 엔딩 전체를 대체합니다(최대 10개, 배열 순서가 표시 순서). " +
            "빈 배열이면 전부 삭제됩니다. 인증 필수이며 스토리 소유자만 교체할 수 있습니다(비소유자·게스트 스토리는 403). " +
            "개수 초과·필드 누락은 400, 스토리가 없으면 404, 시작 설정이 없으면 409입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "교체 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = StoryEndingResponse::class)))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(개수 초과·필드 누락 등)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "스토리 소유자가 아님",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "시작 설정이 없어 엔딩을 저장할 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PutMapping
    fun replaceEndings(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: ReplaceEndingsRequest,
    ): List<StoryEndingResponse> {
        // 교체는 인증 필수(SecurityConfig). 토큰은 유효하나 사용자가 사라졌으면 userId가 null이라 401로 통일한다.
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return storyEndingService.replaceEndings(storyId, ownerId, request)
    }
}
