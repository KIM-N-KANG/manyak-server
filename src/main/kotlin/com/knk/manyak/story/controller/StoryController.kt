package com.knk.manyak.story.controller

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.service.StoryService
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
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Stories", description = "이야기 API")
@Validated
@RestController
@RequestMapping("/api/v1/stories")
class StoryController(
    private val storyService: StoryService,
) {

    @Operation(
        summary = "스토리 ID 목록으로 이야기 목록 조회",
        description = "클라이언트가 로컬스토리지에 보관 중인 storyId 목록으로 스토리 카드 목록을 조회합니다. 로그인 사용자 소유권 조회가 아니라 MVP용 로컬 ID 기반 조회입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [
                    Content(
                        array = ArraySchema(
                            schema = Schema(implementation = StorySummaryResponse::class),
                            arraySchema = Schema(
                                example = """[{"id":1,"title":"달빛 아래의 계약","oneLineIntro":"기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기","genres":["판타지","미스터리"],"author":null,"chatCount":128,"likeCount":32,"status":"PUBLISHED","createdAt":"2026-06-10T12:00:00Z"},{"id":2,"title":"왕국의 마지막 편지","oneLineIntro":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가가는 이야기","genres":["미스터리","스릴러"],"author":null,"chatCount":84,"likeCount":19,"status":"PUBLISHED","createdAt":"2026-06-10T12:10:00Z"}]""",
                            ),
                        ),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/batch")
    fun getStoriesByIds(
        @Valid @RequestBody request: BatchStoryRequest,
    ): List<StorySummaryResponse> = storyService.getStoriesByIds(request)

    @Operation(
        summary = "이야기 상세 조회",
        description = "목록에서 선택한 이야기의 상세 정보와 플레이 시작에 필요한 정보를 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [
                    Content(schema = Schema(implementation = StoryDetailResponse::class)),
                ],
            ),
            ApiResponse(
                responseCode = "404",
                description = "이야기를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/{storyId}")
    fun getStoryDetail(
        @Parameter(description = "스토리 ID")
        @PathVariable storyId: Long,
    ): StoryDetailResponse = storyService.getStoryDetail(storyId)

    @Operation(
        summary = "이야기 삭제 (소프트 삭제)",
        description = "스토리를 소프트 삭제합니다. 행을 물리 삭제하지 않고 삭제 시각만 기록하며, 이후 목록·상세 조회에서 제외됩니다. " +
            "존재하지 않거나 이미 삭제된 스토리는 404로 응답합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "204",
                description = "삭제 성공",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "이야기를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{storyId}")
    fun deleteStory(
        @Parameter(description = "스토리 ID")
        @PathVariable storyId: Long,
    ) = storyService.deleteStory(storyId)
}
