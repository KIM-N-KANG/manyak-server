package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatHistoryMessage
import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnStartSettings
import com.knk.manyak.chat.client.ChatTurnStorySettings
import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatStreamCompletedEvent
import com.knk.manyak.chat.dto.ChatStreamErrorEvent
import com.knk.manyak.chat.dto.ChatStreamStartedEvent
import com.knk.manyak.chat.dto.ChatStreamTokenEvent
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
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
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
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storyPlaySessionRepository: StoryPlaySessionRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val chatTurnAiClient: ChatTurnAiClient,
    private val chatTurnPersister: ChatTurnPersister,
    @Value("\${manyak.chat.history-turns:10}")
    private val historyTurns: Int,
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

    /**
     * 채팅 턴을 SSE로 스트리밍한다.
     *
     * 세션 검증과 AI 요청 재료 조립은 동기로 끝내 잘못된 요청은 즉시 404/400으로 응답하고,
     * 실제 토큰 스트리밍과 저장은 [chatSseExecutor] 위에서 비동기로 처리한다.
     * 스트리밍 동안에는 트랜잭션을 점유하지 않고, 저장은 completed 시점에 [ChatTurnPersister]가
     * 단일 트랜잭션으로 원자적으로 수행한다.
     */
    fun streamChatTurn(
        chatId: Long,
        request: ContinueChatRequest,
    ): SseEmitter {
        val aiRequest = assembleAiRequest(chatId, request.userInput)

        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)
        val futureRef = AtomicReference<CompletableFuture<Void>>()

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
                        .data(ChatStreamStartedEvent(chatId)),
                )
                val result = chatTurnAiClient.streamTurn(aiRequest) { token ->
                    if (Thread.currentThread().isInterrupted) {
                        return@streamTurn
                    }
                    emitter.send(
                        SseEmitter.event()
                            .name("token")
                            .data(ChatStreamTokenEvent(token)),
                    )
                }
                val turnId = chatTurnPersister.persistTurn(
                    playSessionId = chatId,
                    userInput = request.userInput,
                    aiOutput = result.aiOutput,
                    choices = result.choices,
                )
                emitter.send(
                    SseEmitter.event()
                        .name("completed")
                        .data(
                            ChatStreamCompletedEvent(
                                chatId = chatId,
                                turnId = turnId,
                                aiOutput = result.aiOutput,
                                choices = result.choices,
                            ),
                        ),
                )
                emitter.complete()
            } catch (exception: ChatTurnAiException) {
                // AI가 내려준 구조화 오류는 code·message를 그대로 relay한다.
                sendErrorQuietly(emitter, exception.code, exception.message)
                emitter.complete()
            } catch (exception: Exception) {
                // 백엔드 자체 오류는 자체 코드로 응답한다.
                sendErrorQuietly(emitter, "AI_STREAM_FAILED", "AI 응답 생성 중 오류가 발생했습니다.")
                emitter.complete()
            }
        }, chatSseExecutor)
        futureRef.set(future)

        return emitter
    }

    /**
     * 세션을 검증하고(없으면 404) AI 채팅 턴 요청 재료를 조립한다.
     * 오프닝은 [ChatTurnStartSettings]로만 전달하고 history에는 포함하지 않으며,
     * 현재 입력은 아직 저장 전이므로 history에 들어가지 않는다.
     */
    private fun assembleAiRequest(chatId: Long, userInput: String): ChatTurnAiRequest {
        val session = storyPlaySessionRepository.findById(chatId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.") }

        val genre = storyRepository.findById(session.storyId)
            .map { it.genre }
            .orElse(null)
            .orEmpty()
        val setting = storySettingRepository.findByStoryId(session.storyId)
        val startSetting = storyStartSettingRepository.findByStoryId(session.storyId)

        return ChatTurnAiRequest(
            genre = genre,
            storySettings = ChatTurnStorySettings(
                worldSetting = setting?.worldSetting.orEmpty(),
                characterSetting = setting?.characterSetting.orEmpty(),
                userRoleSetting = setting?.userRoleSetting.orEmpty(),
                ruleSetting = setting?.ruleSetting.orEmpty(),
            ),
            startSettings = ChatTurnStartSettings(
                name = startSetting?.name.orEmpty(),
                prologue = startSetting?.prologue.orEmpty(),
                startSituation = startSetting?.startSituation.orEmpty(),
            ),
            history = assembleHistory(chatId),
            userInput = userInput,
            summary = "",
        )
    }

    /**
     * 최근 [historyTurns]턴(USER+ASSISTANT)을 시간순으로 조립한다.
     * 한 턴은 메시지 2건이므로 마지막 `historyTurns * 2`건만 조회해 메모리 로드를 제한하고,
     * SYSTEM 메시지는 AI history에서 제외한다.
     */
    private fun assembleHistory(chatId: Long): List<ChatHistoryMessage> {
        val recent = storyMessageRepository.findByPlaySessionIdOrderByMessageOrderDesc(
            chatId,
            PageRequest.of(0, historyTurns * 2),
        )
        return recent.asReversed().mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> ChatHistoryMessage(ChatMessageRole.USER, message.content)
                MessageRole.ASSISTANT -> ChatHistoryMessage(ChatMessageRole.ASSISTANT, message.content)
                MessageRole.SYSTEM -> null
            }
        }
    }

    private fun sendErrorQuietly(emitter: SseEmitter, code: String, message: String) {
        try {
            emitter.send(
                SseEmitter.event()
                    .name("error")
                    .data(ChatStreamErrorEvent(code, message)),
            )
        } catch (ignored: Exception) {
            // 이미 끊긴 연결로의 추가 전송 실패는 무시한다.
        }
    }

    private companion object {
        const val SSE_TIMEOUT_MILLIS = 60_000L
    }
}
