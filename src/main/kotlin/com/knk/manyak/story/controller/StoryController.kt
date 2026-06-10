package com.knk.manyak.story.controller

import com.knk.manyak.global.response.PageResponse
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.GenerateGeneralStoryDraftRequest
import com.knk.manyak.story.dto.GenerateGeneralStoryDraftResponse
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.StoryCreateResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryGenre
import com.knk.manyak.story.dto.StorySort
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.service.StoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Stories", description = "이야기 API")
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
            ApiResponse(responseCode = "201", description = "스토리라인 생성 성공"),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음"),
            ApiResponse(responseCode = "401", description = "인증 토큰이 없거나 유효하지 않음"),
        ],
    )
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/stories/simple/storylines")
    fun generateSimpleStorylines(
        @Valid @RequestBody request: GenerateSimpleStorylinesRequest,
    ): GenerateSimpleStorylinesResponse = storyService.generateSimpleStorylines(request)

    @Operation(
        summary = "간편 제작 일반 모드 초안 생성",
        description = "선택한 스토리라인과 추가 정보를 바탕으로 일반 모드 입력 필드 초안을 생성합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "일반 모드 초안 생성 성공"),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음"),
            ApiResponse(responseCode = "401", description = "인증 토큰이 없거나 유효하지 않음"),
            ApiResponse(responseCode = "404", description = "간편 제작 진행 정보 또는 스토리라인을 찾을 수 없음"),
        ],
    )
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/stories/simple/general-draft")
    fun generateGeneralStoryDraft(
        @Valid @RequestBody request: GenerateGeneralStoryDraftRequest,
    ): GenerateGeneralStoryDraftResponse = storyService.generateGeneralStoryDraft(request)

    @Operation(
        summary = "일반 모드 이야기 생성",
        description = "커버 이미지, 제목, 소개, 장르, 프롬프트, 스토리/인물 정보, 시작 상황, 등록 정보를 입력해 이야기를 임시 저장하거나 등록합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "이야기 생성 성공"),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음"),
            ApiResponse(responseCode = "401", description = "인증 토큰이 없거나 유효하지 않음"),
        ],
    )
    @SecurityRequirement(name = "bearerAuth")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/stories/general")
    fun createGeneralStory(
        @Valid @RequestBody request: CreateGeneralStoryRequest,
    ): StoryCreateResponse = storyService.createGeneralStory(request)

    @Operation(
        summary = "이야기 목록 조회",
        description = "공개된 이야기 목록을 장르와 정렬 조건으로 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "쿼리 파라미터가 올바르지 않음"),
        ],
    )
    @GetMapping("/stories")
    fun getStories(
        @Parameter(description = "장르 필터")
        @RequestParam(required = false)
        genre: StoryGenre?,

        @Parameter(description = "정렬 기준")
        @RequestParam(defaultValue = "NEW")
        sort: StorySort,

        @Parameter(description = "페이지 번호. 0부터 시작합니다.")
        @Min(0)
        @RequestParam(defaultValue = "0")
        page: Int,

        @Parameter(description = "한 페이지에 조회할 데이터 수")
        @Min(1)
        @Max(100)
        @RequestParam(defaultValue = "20")
        size: Int,
    ): PageResponse<StorySummaryResponse> =
        storyService.getStories(genre = genre, sort = sort, page = page, size = size)

    @Operation(
        summary = "이야기 상세 조회",
        description = "목록에서 선택한 이야기의 상세 정보와 플레이 시작에 필요한 정보를 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "404", description = "이야기를 찾을 수 없음"),
        ],
    )
    @GetMapping("/stories/{storyId}")
    fun getStoryDetail(
        @Parameter(description = "스토리 ID")
        @PathVariable storyId: Long,
    ): StoryDetailResponse = storyService.getStoryDetail(storyId)

    @Operation(
        summary = "내가 만든 이야기 목록 조회",
        description = "현재 로그인한 사용자가 만든 이야기 목록을 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "400", description = "쿼리 파라미터가 올바르지 않음"),
            ApiResponse(responseCode = "401", description = "인증 토큰이 없거나 유효하지 않음"),
        ],
    )
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/stories")
    fun getMyStories(
        @Parameter(description = "등록 상태 필터")
        @RequestParam(required = false)
        status: StoryStatus?,

        @Parameter(description = "페이지 번호. 0부터 시작합니다.")
        @Min(0)
        @RequestParam(defaultValue = "0")
        page: Int,

        @Parameter(description = "한 페이지에 조회할 데이터 수")
        @Min(1)
        @Max(100)
        @RequestParam(defaultValue = "20")
        size: Int,
    ): PageResponse<StorySummaryResponse> =
        storyService.getMyStories(status = status, page = page, size = size)
}
