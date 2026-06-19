package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ChatTurnResponse
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryPlaySession
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
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
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
) {

    @Transactional
    fun createChat(request: CreateChatRequest): CreateChatResponse {
        // 시작 설정은 stories에 FK가 걸려 있어 존재하면 스토리도 반드시 존재한다.
        // 따라서 시작 설정을 먼저 조회하고, 없을 때만 스토리 존재 여부를 확인해 불필요한 조회를 줄인다.
        val startSetting = storyStartSettingRepository.findByStoryId(request.storyId)
        if (startSetting == null && !storyRepository.existsById(request.storyId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }

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

    @Transactional(readOnly = true)
    fun getChatsByIds(request: BatchChatRequest): List<ChatSummaryResponse> {
        val sessionsById = storyPlaySessionRepository.findAllById(request.chatIds)
            .associateBy { it.id }
        // 요청 순서를 보존하고, 존재하지 않는 채팅 ID는 응답에서 제외한다.
        val sessions = request.chatIds.mapNotNull { sessionsById[it] }
        if (sessions.isEmpty()) {
            return emptyList()
        }

        val titlesByStoryId = storyRepository.findAllById(sessions.map { it.storyId })
            .associate { it.id to it.title }

        // 세션별 마지막 ASSISTANT 메시지만 한 번의 쿼리로 조회해 프리뷰로 사용한다.
        val lastPreviewBySessionId = storyMessageRepository
            .findLatestMessagesByPlaySessionIdsAndRole(sessions.map { it.id }, MessageRole.ASSISTANT)
            .associate { it.playSessionId to it.content }

        return sessions.map { session ->
            ChatSummaryResponse(
                id = session.id,
                storyId = session.storyId,
                storyTitle = titlesByStoryId[session.storyId].orEmpty(),
                lastStoryPreview = lastPreviewBySessionId[session.id].orEmpty(),
                updatedAt = session.updatedAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getChatDetail(chatId: Long): ChatDetailResponse {
        val session = storyPlaySessionRepository.findById(chatId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.") }

        val storyTitle = storyRepository.findById(session.storyId)
            .map { it.title }
            .orElse("")
        val prologue = storyStartSettingRepository.findByStoryId(session.storyId)?.prologue.orEmpty()

        val messages = storyMessageRepository.findByPlaySessionIdOrderByMessageOrderAsc(session.id)
        val turns = pairTurns(messages)

        val choicesByMessageId = if (turns.isEmpty()) {
            emptyMap()
        } else {
            storyChoiceRepository.findByMessageIdInOrderByChoiceOrderAsc(turns.map { it.id })
                .groupBy { it.messageId }
                .mapValues { (_, choices) -> choices.map { it.choiceText } }
        }

        return ChatDetailResponse(
            id = session.id,
            storyId = session.storyId,
            storyTitle = storyTitle,
            prologue = prologue,
            turns = turns.map { assistant ->
                ChatTurnResponse(
                    id = assistant.id,
                    userInput = assistant.userInput,
                    aiOutput = assistant.content,
                    choices = choicesByMessageId[assistant.id].orEmpty(),
                    createdAt = assistant.createdAt,
                )
            },
        )
    }

    /**
     * 메시지를 messageOrder 순으로 훑으며 USER 입력 직후의 ASSISTANT 응답을 한 턴으로 묶는다.
     * 짝을 이루지 못한 USER나 SYSTEM 메시지는 턴에서 제외한다. turnId는 ASSISTANT 메시지 id다.
     */
    private fun pairTurns(messages: List<StoryMessage>): List<PairedTurn> {
        val turns = mutableListOf<PairedTurn>()
        var pendingUser: StoryMessage? = null
        for (message in messages) {
            when (message.role) {
                MessageRole.USER -> pendingUser = message
                MessageRole.ASSISTANT -> {
                    pendingUser?.let { user ->
                        turns += PairedTurn(
                            id = message.id,
                            userInput = user.content,
                            content = message.content,
                            createdAt = message.createdAt,
                        )
                    }
                    pendingUser = null
                }
                MessageRole.SYSTEM -> Unit
            }
        }
        return turns
    }

    private data class PairedTurn(
        val id: Long,
        val userInput: String,
        val content: String,
        val createdAt: Instant,
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
