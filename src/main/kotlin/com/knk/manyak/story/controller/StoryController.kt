package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.LorebookListItemResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryPageResponse
import com.knk.manyak.story.dto.StoryReportRequest
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.service.GeneralStoryCreationService
import com.knk.manyak.story.service.StoryListSort
import com.knk.manyak.story.service.StoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
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
    private val generalStoryCreationService: GeneralStoryCreationService,
) {

    @Operation(
        summary = "일반 제작 스토리 등록",
        description = "폼에 직접 입력한 스토리 구성 항목을 한 번에 등록합니다(단발, 임시저장 없음). 인증은 선택이며 " +
            "유효 토큰이면 생성자 소유가 됩니다. AI를 호출하지 않아 크레딧 소모·게스트 한도 카운트가 없습니다. " +
            "응답은 간편 제작과 동일합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "등록 성공",
                content = [Content(schema = Schema(implementation = SimpleStoryCreateResponse::class))],
            ),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/general")
    fun createGeneralStory(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: CreateGeneralStoryRequest,
    ): SimpleStoryCreateResponse = generalStoryCreationService.createGeneralStory(request, userId)

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
                                example = """[{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","title":"달빛 아래의 계약","oneLineIntro":"기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기","genres":["판타지","미스터리"],"author":null,"turnCount":128,"likeCount":32,"status":"PUBLISHED","createdAt":"2026-06-10T12:00:00Z"},{"id":"9c5b94b1-35ad-49bb-b118-8e8fc24abf80","title":"왕국의 마지막 편지","oneLineIntro":"비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가가는 이야기","genres":["미스터리","스릴러"],"author":null,"turnCount":84,"likeCount":19,"status":"PUBLISHED","createdAt":"2026-06-10T12:10:00Z"}]""",
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
        summary = "오리지널 스토리 목록 조회",
        description = "마냑 공식 계정 소유의 공개 스토리 카드를 등록순으로 반환합니다. 피드·검색이 나오기 전까지 " +
            "홈의 오리지널 섹션이 사용하며, 인증은 필요 없습니다. 공식 계정 미설정 환경은 빈 목록입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = StorySummaryResponse::class)))],
            ),
        ],
    )
    @GetMapping("/originals")
    fun getOriginalStories(): List<StorySummaryResponse> = storyService.getOriginalStories()

    @Operation(
        summary = "공개 스토리 목록 조회",
        description = "발행·공개 상태의 회원 스토리 카드를 커서 페이지네이션으로 반환합니다(KNK-149). 인증은 필요 " +
            "없고 요청자 신원도 쓰지 않습니다. 정렬은 latest(기본, 등록 최신순)와 popular(좋아요 많은 순)이며, " +
            "다음 페이지는 응답의 nextCursor를 **같은 sort로** 다시 넘겨 읽습니다. 소프트 삭제·비공개·초안과 " +
            "게스트 제작 스토리(소유자 없음)는 제외합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = StoryPageResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "알 수 없는 sort, 숫자가 아닌 limit, 형식이 깨졌거나 정렬이 다른 cursor",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping
    fun getPublicStories(
        @Parameter(description = "정렬. latest(기본) 또는 popular", example = "latest")
        @RequestParam(defaultValue = "latest") sort: String,
        @Parameter(description = "한 페이지 개수(기본 20, 1~50으로 보정)")
        @RequestParam(defaultValue = "$DEFAULT_LIMIT") limit: Int,
        @Parameter(description = "이전 응답의 nextCursor. 첫 페이지는 생략합니다.")
        @RequestParam(required = false) cursor: String?,
    ): StoryPageResponse =
        storyService.getPublicStories(
            sort = StoryListSort.from(sort),
            limit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT),
            rawCursor = cursor,
        )

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
        summary = "스토리 좋아요 등록",
        description = "스토리에 좋아요를 등록합니다(like만 있고 dislike는 없습니다). 인증 필수이며(게스트 불가) " +
            "이미 좋아요한 스토리를 다시 등록해도 같은 204로 응답합니다(멱등). 읽을 수 없는 스토리(타인의 비공개·초안)는 " +
            "존재 여부를 노출하지 않기 위해 404로 응답합니다. 계정 상태로는 정지 계정이 403, 탈퇴 계정이 401입니다(§4-5 B20).",
    )
    @SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "등록 성공(재등록 포함)", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음(읽기 불가 포함)", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/{storyId}/like")
    fun likeStory(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ) = storyService.like(storyId, requireUser(userId))

    @Operation(
        summary = "스토리 좋아요 취소",
        description = "스토리 좋아요를 취소합니다. 인증 필수이며(게스트 불가) 좋아요하지 않은 스토리를 취소해도 " +
            "같은 204로 응답합니다(멱등). 읽을 수 없는 스토리는 404로 응답합니다. 계정 상태로는 정지 계정이 403, " +
            "탈퇴 계정이 401입니다(§4-5 B20).",
    )
    @SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "취소 성공(좋아요가 없던 경우 포함)", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음(읽기 불가 포함)", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{storyId}/like")
    fun unlikeStory(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
    ) = storyService.unlike(storyId, requireUser(userId))

    @Operation(
        summary = "스토리 신고 등록",
        description = "스토리를 신고합니다. 인증 필수이며(게스트 불가) 같은 스토리를 다시 신고해도 같은 201로 응답합니다" +
            "(멱등 — 행이 늘거나 알림이 중복 발송되지 않습니다). 읽을 수 없는 스토리(타인의 비공개·초안)는 존재 여부를 " +
            "노출하지 않기 위해 404로 응답합니다. 계정 상태로는 정지 계정이 403, 탈퇴 계정이 401입니다(§4-5 B20).",
    )
    @SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "신고 접수(재신고 포함)", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음(알 수 없는 사유, 상세 500자 초과)", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음(읽기 불가 포함)", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{storyId}/reports")
    fun reportStory(
        @Parameter(description = "스토리 ID(공개 식별자)")
        @PathVariable storyId: String,
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: StoryReportRequest,
    ) = storyService.report(storyId, requireUser(userId), request.reason, request.detail)

    @Operation(
        summary = "스토리 삭제 (소프트 삭제)",
        description = "스토리를 소프트 삭제합니다. 행을 물리 삭제하지 않고 삭제 시각만 기록하며, 이후 목록·상세 조회에서 제외됩니다. " +
            "인증은 선택이며 회원 소유 스토리는 소유자만(타인·미인증 403), 소유자 없는 게스트 스토리는 허용합니다. " +
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
                responseCode = "403",
                description = "스토리 소유자가 아님",
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
        @CurrentUserId userId: Long?,
    ) = storyService.deleteStory(storyId, userId)

    // 좋아요 경로는 anyRequest().authenticated()로 보호되지만, 토큰은 유효하나 사용자가 사라진 경우 null이 올 수 있어 401로 통일한다.
    private fun requireUser(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
    }
}
