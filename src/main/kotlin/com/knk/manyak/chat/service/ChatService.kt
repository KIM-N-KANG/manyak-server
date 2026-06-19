package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ChatTurnResponse
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

@Service
class ChatService(
    @Qualifier("chatSseExecutor")
    private val chatSseExecutor: Executor,
    private val storyRepository: StoryRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storyPlaySessionRepository: StoryPlaySessionRepository,
) {

    @Transactional
    fun createChat(request: CreateChatRequest): CreateChatResponse {
        if (!storyRepository.existsById(request.storyId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }

        val startSetting = storyStartSettingRepository.findByStoryId(request.storyId)
        val session = storyPlaySessionRepository.save(
            StoryPlaySession(
                storyId = request.storyId,
                startSettingId = startSetting?.id,
            ),
        )

        return CreateChatResponse(
            id = session.id,
            storyId = session.storyId,
            prologue = startSetting?.prologue.orEmpty(),
            createdAt = session.createdAt,
        )
    }

    fun getChatsByIds(request: BatchChatRequest): List<ChatSummaryResponse> =
        request.chatIds.mapIndexed { index, chatId ->
            ChatSummaryResponse(
                id = chatId,
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
            id = chatId,
            storyId = 1L,
            storyTitle = "호아킨 아카데미의 무속성 신입생",
            prologue = "마법 세계에서 당신은 호아킨 아카데미의 1학년으로 입학했다. 입학식 전 수행되는 적성 검사. 묘한 긴장감이 검사장을 감싼다.",
            turns = listOf(
                ChatTurnResponse(
                    id = 1L,
                    userInput = "이름은 강진우고 무속성 판정을 받은 호아킨 아카데미 1학년이야.",
                    aiOutput = "강진우라는 이름이 검사장 한쪽 기록판에 새겨졌다. 무속성이라는 판정은 조용한 웅성거림을 불러왔다.",
                    createdAt = Instant.now(),
                ),
                ChatTurnResponse(
                    id = 2L,
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
        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)
        val futureRef = AtomicReference<CompletableFuture<Void>>()
        val output = "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다. ${request.userInput.take(24)} 그 장면은 모두의 기억에 깊게 새겨졌다."

        emitter.onTimeout {
            futureRef.get()?.cancel(true)
            emitter.complete()
        }
        emitter.onCompletion {
            futureRef.get()?.cancel(true)
        }
        emitter.onError {
            futureRef.get()?.cancel(true)
        }

        val future = CompletableFuture.runAsync({
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("started")
                        .data(mapOf("chatId" to chatId)),
                )
                for (char in output) {
                    if (Thread.currentThread().isInterrupted) {
                        return@runAsync
                    }
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
        }, chatSseExecutor)
        futureRef.set(future)

        return emitter
    }

    private companion object {
        const val SSE_TIMEOUT_MILLIS = 60_000L
    }
}
