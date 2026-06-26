package com.knk.manyak.story.controller

import com.knk.manyak.global.error.ApiErrorResponse
import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryTagListItemResponse
import com.knk.manyak.story.dto.StorylineRatingRequest
import com.knk.manyak.story.dto.StorylineRatingResponse
import com.knk.manyak.story.service.SimpleStoryCreationService
import com.knk.manyak.story.service.StorylineRatingService
import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Simple Story Creation", description = "간편 제작 API")
@Validated
@RestController
@RequestMapping("/api/v1/stories/simple")
class SimpleStoryCreationController(
    private val simpleStoryCreationService: SimpleStoryCreationService,
    private val storylineRatingService: StorylineRatingService,
) {

    @Operation(
        summary = "간편 제작 태그 목록 조회",
        description = "간편 제작 키워드 선택 화면에서 사용할 사전 정의 태그 목록을 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [
                    Content(
                        array = ArraySchema(
                            schema = Schema(implementation = SimpleStoryTagListItemResponse::class),
                        ),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/tags")
    fun getSimpleStoryTags(): List<SimpleStoryTagListItemResponse> =
        simpleStoryCreationService.getSimpleStoryTags()

    @Operation(
        summary = "간편 제작 스토리라인 생성",
        description = "사용자가 선택한 태그를 저장하고 AI 서버에 전달해 예시 스토리라인 3개와 추천 추가 정보를 생성합니다.",
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
            ApiResponse(
                responseCode = "502",
                description = "AI 서버 요청 실패",
                content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/storylines")
    fun generateSimpleStorylines(
        @Valid @RequestBody request: GenerateSimpleStorylinesRequest,
        // optional 인증: 유효 access 토큰이면 로그인 사용자 내부 id, 익명이면 null.
        @CurrentUserId userId: Long?,
    ): GenerateSimpleStorylinesResponse =
        simpleStoryCreationService.generateSimpleStorylines(request, userId)

    @Operation(
        summary = "간편 제작 이야기 생성",
        description = "선택한 스토리라인과 추가 정보를 AI 서버에 전달해 최종 스토리를 생성하고 저장합니다. " +
            "응답으로 받은 id는 클라이언트 로컬스토리지에 저장해 내 스토리 목록 구성에 사용합니다.",
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
            ApiResponse(
                responseCode = "409",
                description = "이미 이야기가 생성된 간편 제작 진행",
                content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
            ),
            ApiResponse(
                responseCode = "502",
                description = "AI 서버 요청 실패",
                content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    fun createSimpleStory(
        @Valid @RequestBody request: CreateSimpleStoryRequest,
        // optional 인증: 유효 access 토큰이면 로그인 사용자 내부 id, 익명이면 null.
        @CurrentUserId userId: Long?,
    ): SimpleStoryCreateResponse =
        simpleStoryCreationService.createSimpleStory(request, userId)

    @Operation(
        summary = "스토리라인 평가 설정/변경",
        description = "간편 제작으로 생성된 예시 스토리라인에 좋아요/나빠요 평가를 남깁니다. " +
            "같은 스토리라인을 다시 평가하면 값이 갱신됩니다(대상당 1개). 취소는 DELETE를 사용합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "평가 성공",
                content = [Content(schema = Schema(implementation = StorylineRatingResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "스토리라인을 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PutMapping("/storylines/{storylineId}/rating")
    fun rateStoryline(
        @PathVariable storylineId: Long,
        @Valid @RequestBody request: StorylineRatingRequest,
        // optional 인증: 유효 access 토큰이면 로그인 사용자 내부 id, 익명이면 null.
        @CurrentUserId userId: Long?,
    ): StorylineRatingResponse =
        storylineRatingService.rate(storylineId, request.rating!!, userId)

    @Operation(
        summary = "스토리라인 평가 취소",
        description = "스토리라인 평가를 제거합니다. 평가가 없어도 멱등하게 204를 반환합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "취소 성공"),
            ApiResponse(
                responseCode = "404",
                description = "스토리라인을 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/storylines/{storylineId}/rating")
    fun cancelStorylineRating(
        @PathVariable storylineId: Long,
    ) = storylineRatingService.cancel(storylineId)
}
