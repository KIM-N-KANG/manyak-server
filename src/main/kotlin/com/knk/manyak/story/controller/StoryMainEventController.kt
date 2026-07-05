package com.knk.manyak.story.controller

import com.knk.manyak.story.dto.ReplaceMainEventsRequest
import com.knk.manyak.story.dto.StoryMainEventResponse
import com.knk.manyak.story.service.StoryMainEventService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Stories", description = "스토리 API")
@RestController
@RequestMapping("/api/v1/stories/{storyId}/main-events")
class StoryMainEventController(
    private val storyMainEventService: StoryMainEventService,
) {

    @Operation(
        summary = "주요 사건 목록 조회",
        description = "스토리의 주요 사건을 표시 순서로 조회합니다. 없으면 빈 배열입니다. 스토리가 없으면 404입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = StoryMainEventResponse::class)))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping
    fun getMainEvents(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
    ): List<StoryMainEventResponse> = storyMainEventService.getMainEvents(storyId)

    @Operation(
        summary = "주요 사건 교체 저장",
        description = "보낸 목록으로 스토리의 주요 사건 전체를 대체합니다(최대 10개, 배열 순서가 표시 순서). " +
            "빈 배열이면 전부 삭제됩니다. 개수 초과·필드 누락은 400, 스토리가 없으면 404입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "교체 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = StoryMainEventResponse::class)))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(개수 초과·필드 누락 등)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PutMapping
    fun replaceMainEvents(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @Valid @RequestBody request: ReplaceMainEventsRequest,
    ): List<StoryMainEventResponse> = storyMainEventService.replaceMainEvents(storyId, request)
}
