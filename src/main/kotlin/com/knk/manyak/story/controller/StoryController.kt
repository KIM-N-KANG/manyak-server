package com.knk.manyak.story.controller

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.service.StoryService
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
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
@RequestMapping("/api/v1")
class StoryController(
    private val storyService: StoryService,
) {

    @Operation(
        summary = "간편 제작 스토리라인 생성",
        description = "사용자가 선택한 태그를 저장하고 AI 서버에 전달해 예시 스토리라인 3개와 도움 질문을 생성합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "스토리라인 생성 성공",
                content = [
                    Content(schema = Schema(implementation = GenerateSimpleStorylinesResponse::class)),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/stories/simple/storylines")
    fun generateSimpleStorylines(
        @Valid @RequestBody request: GenerateSimpleStorylinesRequest,
    ): GenerateSimpleStorylinesResponse = storyService.generateSimpleStorylines(request)

    @Operation(
        summary = "간편 제작 이야기 생성",
        description = "선택한 스토리라인과 추가 정보를 바탕으로 이야기를 바로 저장합니다. 응답으로 받은 storyId는 클라이언트 로컬스토리지에 저장해 내 스토리 목록 구성에 사용합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "간편 제작 이야기 생성 성공",
                content = [
                    Content(schema = Schema(implementation = SimpleStoryCreateResponse::class)),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "간편 제작 진행 정보 또는 스토리라인을 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/stories/simple")
    fun createSimpleStory(
        @Valid @RequestBody request: CreateSimpleStoryRequest,
    ): SimpleStoryCreateResponse = storyService.createSimpleStory(request)

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
                                example = """[{"id":1,"title":"달빛 아래의 계약","summary":"기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기","genres":["FANTASY","MYSTERY"],"authorNickname":null,"chatCount":128,"likeCount":32,"status":"PUBLISHED","createdAt":"2026-06-10T12:00:00Z"},{"id":2,"title":"왕국의 마지막 편지","summary":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가가는 이야기","genres":["MYSTERY","THRILLER"],"authorNickname":null,"chatCount":84,"likeCount":19,"status":"PUBLISHED","createdAt":"2026-06-10T12:10:00Z"}]""",
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
    @PostMapping("/stories/batch")
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
    @GetMapping("/stories/{storyId}")
    fun getStoryDetail(
        @Parameter(description = "스토리 ID")
        @PathVariable storyId: Long,
    ): StoryDetailResponse = storyService.getStoryDetail(storyId)
}
