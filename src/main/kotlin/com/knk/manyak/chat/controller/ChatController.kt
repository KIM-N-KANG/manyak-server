package com.knk.manyak.chat.controller

import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.service.ChatService
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
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
        description = "스토리에서 채팅을 시작하고 첫 프롤로그와 캐릭터 설정 안내 문구를 반환합니다.",
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
    ): CreateChatResponse = chatService.createChat(request)

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
                                example = """[{"id":10,"storyId":1,"storyTitle":"호아킨 아카데미의 무속성 신입생","lastStoryPreview":"검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.","updatedAt":"2026-06-12T12:10:00Z"},{"id":11,"storyId":2,"storyTitle":"왕국의 마지막 편지","lastStoryPreview":"봉인이 풀린 편지 끝에서 오래된 왕가의 문장이 희미하게 떠올랐다.","updatedAt":"2026-06-12T12:20:00Z"}]""",
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
        description = "프롤로그, 캐릭터 설정 안내, 지금까지의 사용자 입력과 AI 이어쓰기 결과를 조회합니다.",
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
        @Parameter(description = "채팅 ID")
        @PathVariable chatId: Long,
    ): ChatDetailResponse = chatService.getChatDetail(chatId)

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
                            example = "event: started\ndata: {\"chatId\":10}\n\nevent: token\ndata: {\"text\":\"검\"}\n\nevent: token\ndata: {\"text\":\"사\"}\n\nevent: completed\ndata: {\"chatId\":10,\"turnId\":3,\"aiOutput\":\"검사장은 한순간 숨소리조차 사라진 듯 조용해졌다.\"}\n\nevent: error\ndata: {\"code\":\"AI_STREAM_FAILED\",\"message\":\"AI 응답 생성 중 오류가 발생했습니다.\"}\n\n",
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
        @Parameter(description = "채팅 ID")
        @PathVariable chatId: Long,
        @Valid @RequestBody request: ContinueChatRequest,
    ): SseEmitter = chatService.streamChatTurn(chatId = chatId, request = request)
}
