package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.CreateStoryDraftRequest
import com.knk.manyak.story.dto.LorebookListItemResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryDraftResponse
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Stories", description = "스토리 API")
@Validated
@RestController
@RequestMapping("/api/v1/stories")
class StoryController(
    private val storyService: StoryService,
) {

    @Operation(
        summary = "일반 모드 초안 생성",
        description = "빈 초안(status=DRAFT, visibility=PRIVATE) 스토리를 생성합니다. 인증 필수이며 생성자 소유가 됩니다. " +
            "기본정보는 선택이며, 이후 세계관 등 각 탭이 부분 저장(자동저장)으로 채웁니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "생성 성공",
                content = [Content(schema = Schema(implementation = StoryDraftResponse::class))],
            ),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "401", description = "인증 실패", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    fun createDraft(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody(required = false) request: CreateStoryDraftRequest?,
    ): StoryDraftResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return storyService.createDraft(ownerId, request ?: CreateStoryDraftRequest())
    }

    @Operation(
        summary = "스토리 ID 목록으로 스토리 목록 조회",
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
                                example = """[{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","title":"달빛 아래의 계약","oneLineIntro":"기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기","genres":["판타지","미스터리"],"author":null,"chatCount":128,"likeCount":32,"status":"PUBLISHED","createdAt":"2026-06-10T12:00:00Z"},{"id":"9c5b94b1-35ad-49bb-b118-8e8fc24abf80","title":"왕국의 마지막 편지","oneLineIntro":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가가는 이야기","genres":["미스터리","스릴러"],"author":null,"chatCount":84,"likeCount":19,"status":"PUBLISHED","createdAt":"2026-06-10T12:10:00Z"}]""",
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
        @CurrentUserId userId: Long?,
    ): List<StorySummaryResponse> = storyService.getStoriesByIds(request, userId)

    @Operation(
        summary = "로어북 카탈로그 조회",
        description = "일반 제작에서 참조할 로어북(장르 공용 용어 사전) 목록을 조회합니다. genre로 필터할 수 있습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [
                    Content(
                        array = ArraySchema(
                            schema = Schema(implementation = LorebookListItemResponse::class),
                        ),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/lorebooks")
    fun getLorebooks(
        @Parameter(description = "장르 필터. 생략하면 전체 활성 로어북을 조회합니다.")
        @RequestParam(required = false) genre: String?,
    ): List<LorebookListItemResponse> = storyService.getLorebooks(genre)

    @Operation(
        summary = "스토리 상세 조회",
        description = "목록에서 선택한 스토리의 상세 정보와 플레이 시작에 필요한 정보를 조회합니다.",
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
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/{storyId}")
    fun getStoryDetail(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ): StoryDetailResponse = storyService.getStoryDetail(storyId, userId)

    @Operation(
        summary = "스토리 삭제 (소프트 삭제)",
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
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{storyId}")
    fun deleteStory(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
    ) = storyService.deleteStory(storyId)
}
