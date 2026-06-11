package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ChatTurnResponse
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.CompletableFuture

@Service
class ChatService {

    fun createChat(request: CreateChatRequest): CreateChatResponse =
        CreateChatResponse(
            chatId = 10L,
            storyId = request.storyId,
            prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다. 입학식 전 수행되는 적성 검사. 묘한 긴장감이 검사장을 감싼다.",
            guideMessage = "이름, 성향, 능력치, 배경 등 캐릭터 설정을 자유롭게 입력해주세요. 이후 입력은 이야기 전개에 반영됩니다.",
            createdAt = Instant.now(),
        )

    fun getChatsByIds(request: BatchChatRequest): List<ChatSummaryResponse> =
        request.chatIds.mapIndexed { index, chatId ->
            ChatSummaryResponse(
                chatId = chatId,
                storyId = index + 1L,
                storyTitle = if (index % 2 == 0) "호아킨 아카데미의 무속성 신입생" else "왕국의 마지막 편지",
                lastStoryPreview = if (index % 2 == 0) {
                    "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다."
                } else {
                    "봉인이 풀린 편지 끝에서 오래된 왕가의 문장이 희미하게 떠올랐다."
                },
                updatedAt = Instant.now(),
            )
        }

    fun getChatDetail(chatId: Long): ChatDetailResponse =
        ChatDetailResponse(
            chatId = chatId,
            storyId = 1L,
            storyTitle = "호아킨 아카데미의 무속성 신입생",
            prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다. 입학식 전 수행되는 적성 검사. 묘한 긴장감이 검사장을 감싼다.",
            guideMessage = "이름, 성향, 능력치, 배경 등 캐릭터 설정을 자유롭게 입력해주세요.",
            turns = listOf(
                ChatTurnResponse(
                    turnId = 1L,
                    userInput = "이름은 강진우고 무속성 판정을 받은 호아킨 아카데미 1학년이야.",
                    aiOutput = "강진우라는 이름이 검사장 한쪽 기록판에 새겨졌다. 무속성이라는 판정은 조용한 웅성거림을 불러왔다.",
                    createdAt = Instant.now(),
                ),
                ChatTurnResponse(
                    turnId = 2L,
                    userInput = "마법수정에서 아무 빛도 나오지 않았지만, 내려가는 순간 수정이 금 가더니 깨져버렸다.",
                    aiOutput = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다. 깨질 리 없는 수정의 파편이 단상 위에서 차갑게 빛났다.",
                    createdAt = Instant.now(),
                ),
            ),
        )

    fun streamChatTurn(
        chatId: Long,
        request: ContinueChatRequest,
    ): SseEmitter {
        val emitter = SseEmitter(0L)
        val output = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다. ${request.userInput.take(24)} 그 장면은 모두의 기억에 깊게 새겨졌다."

        CompletableFuture.runAsync {
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("started")
                        .data(mapOf("chatId" to chatId)),
                )
                output.forEach { char ->
                    emitter.send(
                        SseEmitter.event()
                            .name("token")
                            .data(mapOf("text" to char.toString())),
                    )
                }
                emitter.send(
                    SseEmitter.event()
                        .name("completed")
                        .data(mapOf("chatId" to chatId, "turnId" to 3L, "aiOutput" to output)),
                )
                emitter.complete()
            } catch (exception: Exception) {
                emitter.send(
                    SseEmitter.event()
                        .name("error")
                        .data(mapOf("code" to "AI_STREAM_FAILED", "message" to "AI 응답 생성 중 오류가 발생했습니다.")),
                )
                emitter.completeWithError(exception)
            }
        }

        return emitter
    }
}
