package com.knk.manyak.chat.controller

import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.service.ChatService
import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Tag(name = "Chats", description = "채팅 API")
@Validated
@RestController
@RequestMapping("/api/v1")
class ChatController(
    private val chatService: ChatService,
) {

    @Operation(
        summary = "채팅 생성",
        description = "스토리에서 채팅을 시작하고 시작 프롤로그를 반환합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "채팅 생성 성공",
                content = [Content(schema = Schema(implementation = CreateChatResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "스토리를 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/chats")
    fun createChat(
        @Valid @RequestBody request: CreateChatRequest,
        // optional 인증: 유효 access 토큰이면 로그인 사용자 내부 id, 익명이면 null.
        @CurrentUserId userId: Long?,
    ): CreateChatResponse = chatService.createChat(request, userId)

    @Operation(
        summary = "채팅 ID 목록으로 이전 채팅 목록 조회",
        description = "클라이언트가 로컬스토리지에 보관 중인 chatId 목록으로 이전 채팅 목록을 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [
                    Content(
                        array = ArraySchema(
                            schema = Schema(implementation = ChatSummaryResponse::class),
                            arraySchema = Schema(
                                example = """[{"id":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","storyId":"3f2504e0-4f89-41d3-9a0c-0305e82c3301","storyTitle":"호아킨 아카데미의 무속성 신입생","lastStoryPreview":"검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.","turnCount":2,"updatedAt":"2026-06-12T12:10:00Z"},{"id":"9c5b94b1-35ad-49bb-b118-8e8fc24abf80","storyId":"9c5b94b1-35ad-49bb-b118-8e8fc24abf80","storyTitle":"왕국의 마지막 편지","lastStoryPreview":"봉인이 풀린 편지 끝에서 오래된 왕가의 문장이 희미하게 떠올랐다.","turnCount":1,"updatedAt":"2026-06-12T12:20:00Z"}]""",
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
    @PostMapping("/chats/batch")
    fun getChatsByIds(
        @Valid @RequestBody request: BatchChatRequest,
    ): List<ChatSummaryResponse> = chatService.getChatsByIds(request)

    @Operation(
        summary = "채팅 상세 조회",
        description = "프롤로그와 지금까지의 사용자 입력, AI 이어쓰기 결과를 조회합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = ChatDetailResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "채팅을 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/chats/{chatId}")
    fun getChatDetail(
        @Parameter(description = "채팅 ID(공개 식별자)")
        @PathVariable chatId: String,
    ): ChatDetailResponse = chatService.getChatDetail(chatId)

    @Operation(
        summary = "채팅 삭제",
        description = "채팅을 소프트 삭제합니다. 삭제된 채팅은 목록·상세 조회에서 제외됩니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "404",
                description = "채팅을 찾을 수 없음(이미 삭제됨 포함)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/chats/{chatId}")
    fun deleteChat(
        @Parameter(description = "채팅 ID(공개 식별자)")
        @PathVariable chatId: String,
    ) = chatService.deleteChat(chatId)

    @Operation(
        summary = "채팅 이어쓰기 스트리밍",
        description = "사용자 입력을 바탕으로 앞선 채팅 맥락에 이어지는 이야기를 생성하고 SSE로 스트리밍합니다. 사용자 입력은 캐릭터 프로필 설정, 다음 행동, 대사, 분위기, 감정, 연출 방향 등을 모두 포함할 수 있습니다. 스트리밍은 started, token, completed 순서로 전달되며 completed 이벤트에는 최종 저장된 aiOutput 전체가 포함됩니다. 실패 시 error 이벤트를 전달할 수 있습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "SSE 스트리밍 시작 성공",
                content = [
                    Content(
                        mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                        schema = Schema(
                            type = "string",
                            example = "event: started\ndata: {\"chatId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\"}\n\nevent: token\ndata: {\"text\":\"검\"}\n\nevent: token\ndata: {\"text\":\"사\"}\n\nevent: completed\ndata: {\"chatId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"turnId\":3,\"aiOutput\":\"검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.\"}\n\nevent: error\ndata: {\"code\":\"AI_STREAM_FAILED\",\"message\":\"AI 응답 생성 중 오류가 발생했습니다.\"}\n\n",
                        ),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "402",
                description = "크레딧 잔액 부족(회원). SSE를 열기 전 동기 JSON으로 응답합니다.",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "채팅을 찾을 수 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping(
        "/chats/{chatId}/turns/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun streamChatTurn(
        @Parameter(description = "채팅 ID(공개 식별자)")
        @PathVariable chatId: String,
        @Valid @RequestBody request: ContinueChatRequest,
        // optional 인증: 유효 access 토큰이면 로그인 사용자 내부 id, 익명이면 null.
        @CurrentUserId userId: Long?,
        response: HttpServletResponse,
    ): SseEmitter {
        // SSE 본문은 UTF-8 한글을 포함한다. SseEmitter는 Content-Type을 charset 없는
        // text/event-stream으로 고정해 produces의 charset이 무시되므로, 여기서 직접
        // charset=UTF-8을 지정한다. 없으면 일부 클라이언트가 text/* 기본값 ISO-8859-1로
        // 디코딩해 한글이 깨진다. (세션 404는 아래 호출에서 동기로 던져지며, 에러 응답은
        // 메시지 컨버터가 application/json으로 Content-Type을 다시 덮어쓴다.)
        response.contentType = "${MediaType.TEXT_EVENT_STREAM_VALUE};charset=UTF-8"
        return try {
            chatService.streamChatTurn(chatId = chatId, request = request, userId = userId)
        } catch (exception: InsufficientCreditException) {
            // 회원 선차감은 streamChatTurn이 SseEmitter를 만들기 전에 동기로 수행하므로, 잔액 부족 예외는
            // 스트림이 열리기 전에 이 요청 스레드로 전파된다. 여기서 402로 변환해 동기 HTTP 응답으로 돌려준다
            // (스트림 안 error 이벤트가 아님, 스펙 §4-3-7). 공유 @ControllerAdvice를 추가하지 않고 컨트롤러에서 지역 변환한다.
            throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "크레딧이 부족합니다.", exception)
        }
    }
}
