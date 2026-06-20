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
import java.util.UUID
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
            id = session.publicId.toString(),
            storyId = session.storyId,
            prologue = startSetting?.prologue.orEmpty(),
            createdAt = session.createdAt,
        )
    }

    @Transactional(readOnly = true)
    fun getChatsByIds(request: BatchChatRequest): List<ChatSummaryResponse> {
        // 공개 식별자(UUID 문자열)로 받는다. 형식이 잘못된 값은 매칭될 수 없으므로 조용히 제외한다.
        val requestedPublicIds = request.chatIds.mapNotNull { parsePublicIdOrNull(it) }
        val sessionsByPublicId = storyPlaySessionRepository.findAllByPublicIdIn(requestedPublicIds)
            .associateBy { it.publicId }
        // 요청 순서를 보존하고, 존재하지 않거나 형식이 잘못된 채팅 ID는 응답에서 제외한다.
        val sessions = request.chatIds.mapNotNull { raw ->
            parsePublicIdOrNull(raw)?.let { sessionsByPublicId[it] }
        }
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
                id = session.publicId.toString(),
                storyId = session.storyId,
                storyTitle = titlesByStoryId[session.storyId].orEmpty(),
                lastStoryPreview = lastPreviewBySessionId[session.id].orEmpty(),
                // 채팅 횟수는 persistTurn이 턴 저장과 원자적으로 증가시키는 비정규화 카운터를 그대로 읽는다.
                chatCount = session.currentTurn,
                updatedAt = session.updatedAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getChatDetail(chatId: String): ChatDetailResponse {
        val session = resolveSession(chatId)

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
            id = session.publicId.toString(),
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
        chatId: String,
        request: ContinueChatRequest,
    ): SseEmitter {
        // 세션을 공개 식별자로 먼저 검증한다(없으면 동기 404). 이후 내부 PK로 저장·이력을 처리하고,
        // SSE 이벤트에는 외부에 노출하는 공개 식별자(chatId)만 싣는다.
        val session = resolveSession(chatId)
        val sessionId = session.id
        val aiRequest = assembleAiRequest(session, request.userInput)

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
                    playSessionId = sessionId,
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
     * 이미 검증된 세션으로 AI 채팅 턴 요청 재료를 조립한다.
     * 오프닝은 [ChatTurnStartSettings]로만 전달하고 history에는 포함하지 않으며,
     * 현재 입력은 아직 저장 전이므로 history에 들어가지 않는다.
     */
    private fun assembleAiRequest(session: StoryPlaySession, userInput: String): ChatTurnAiRequest {
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
            history = assembleHistory(session.id),
            userInput = userInput,
            summary = "",
        )
    }

    /**
     * 공개 식별자(UUID 문자열)로 세션을 조회한다. 형식이 잘못됐거나 존재하지 않으면 404로 통일한다.
     * 순차 정수든 임의 문자열이든 동일하게 404를 반환해 존재 여부를 노출하지 않는다.
     */
    private fun resolveSession(publicId: String): StoryPlaySession {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
        return storyPlaySessionRepository.findByPublicId(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
    }

    private fun parsePublicIdOrNull(raw: String): UUID? =
        try {
            UUID.fromString(raw)
        } catch (ignored: IllegalArgumentException) {
            null
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
