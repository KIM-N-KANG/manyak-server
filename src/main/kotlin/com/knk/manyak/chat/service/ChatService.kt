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
import com.knk.manyak.global.observability.LengthBuckets
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import org.springframework.beans.factory.annotation.Qualifier
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
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyPlaySessionRepository: StoryPlaySessionRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val chatTurnAiClient: ChatTurnAiClient,
    private val chatTurnPersister: ChatTurnPersister,
    private val structuredLogger: StructuredLogger,
    private val aiCallRecorder: AiCallRecorder,
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
        structuredLogger.event(
            "chat_started",
            "story_id" to session.storyId,
            "chat_id" to session.publicId.toString(),
        )

        val suggestedInputs = loadSuggestedInputs(startSetting?.id)

        return CreateChatResponse(
            id = session.publicId.toString(),
            storyId = session.storyId,
            prologue = startSetting?.prologue.orEmpty(),
            suggestedInputs = suggestedInputs,
            createdAt = session.createdAt,
        )
    }

    @Transactional(readOnly = true)
    fun getChatsByIds(request: BatchChatRequest): List<ChatSummaryResponse> {
        // 공개 식별자(UUID 문자열)로 받는다. 형식이 잘못된 값은 매칭될 수 없으므로 조용히 제외한다.
        val requestedPublicIds = request.chatIds.mapNotNull { parsePublicIdOrNull(it) }
        // 유효한 식별자가 하나도 없으면 DB 조회 없이 즉시 빈 목록을 반환한다.
        if (requestedPublicIds.isEmpty()) {
            return emptyList()
        }
        // 존재하고 삭제되지 않은 채팅만 마지막 진행 시각(updatedAt) 내림차순으로 노출한다.
        // updatedAt이 같으면 id 내림차순으로 결정적 순서를 보장한다. 존재하지 않거나 형식이
        // 잘못된 채팅 ID는 조회되지 않으므로 자연히 제외된다.
        val sessions = storyPlaySessionRepository.findAllByPublicIdInAndDeletedAtIsNull(requestedPublicIds)
            .sortedWith(compareByDescending<StoryPlaySession> { it.updatedAt }.thenByDescending { it.id })
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
        // prologue와 추천 입력 모두 시작 설정에 종속되므로 한 번만 조회해 재사용한다.
        val startSetting = storyStartSettingRepository.findByStoryId(session.storyId)

        val messages = storyMessageRepository.findByPlaySessionIdOrderByMessageOrderAsc(session.id)
        val turns = pairTurns(messages)

        val choicesByMessageId = if (turns.isEmpty()) {
            emptyMap()
        } else {
            storyChoiceRepository.findByMessageIdInOrderByChoiceOrderAsc(turns.map { it.id })
                .groupBy { it.messageId }
                .mapValues { (_, choices) -> choices.map { it.choiceText } }
        }

        // 아직 한 번도 이어쓰지 않은 채팅(turns 비어 있음)만 시작 추천 입력을 채운다.
        // 진행 턴이 있으면 다음 행동은 마지막 턴의 choices로 안내하므로 조회를 생략하고 빈 배열로 둔다.
        val suggestedInputs = if (turns.isEmpty()) loadSuggestedInputs(startSetting?.id) else emptyList()

        return ChatDetailResponse(
            id = session.publicId.toString(),
            storyId = session.storyId,
            storyTitle = storyTitle,
            prologue = startSetting?.prologue.orEmpty(),
            turns = turns.map { assistant ->
                ChatTurnResponse(
                    id = assistant.id,
                    userInput = assistant.userInput,
                    aiOutput = assistant.content,
                    choices = choicesByMessageId[assistant.id].orEmpty(),
                    createdAt = assistant.createdAt,
                )
            },
            suggestedInputs = suggestedInputs,
        )
    }

    /**
     * 채팅을 소프트 삭제한다. 행을 물리 삭제하지 않고 deletedAt만 기록해 자식 데이터(메시지·선택지)를 보존한다.
     * 이미 삭제됐거나 존재하지 않으면(순차 정수·임의 값 포함) 404로 통일한다.
     */
    @Transactional
    fun deleteChat(chatId: String) {
        // 영속 상태 엔티티의 변경은 트랜잭션 커밋 시 더티 체킹으로 반영된다(명시적 save 불필요).
        val session = resolveSession(chatId)
        session.deletedAt = Instant.now()
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
            // AI 호출이 성공 반환하면 채운다. AI 호출 자체 실패는 record의 onFailure에서 캡처하므로 null로 남는다.
            var succeededAiCallLogId: Long? = null
            try {
                emitter.send(
                    SseEmitter.event()
                        .name("started")
                        .data(ChatStreamStartedEvent(chatId)),
                )
                // AI 호출을 ai_call_logs에 적재한다. chatSseExecutor 워커에서 실행되지만
                // MdcTaskDecorator가 request_id 등 MDC를 전파하므로 Recorder가 식별자를 그대로 읽는다.
                // turn_index는 persistTurn이 DB에서 확정한 뒤 attachTurnIndex로 채운다(동시 요청 정합성).
                val recorded = aiCallRecorder.record(
                    AiCallContext(
                        feature = AiCallFeature.CHAT_RESPONSE,
                        storyId = session.storyId,
                        chatId = session.publicId,
                    ),
                    errorCode = { throwable ->
                        if (throwable is ChatTurnAiException) throwable.code else "AI_STREAM_FAILED"
                    },
                    onFailure = { aiCallLogId, throwable ->
                        captureChatFailure(aiCallLogId, throwable, session, chatId)
                    },
                    // chat meta는 completed 결과(ChatTurnAiResult)에 실려 오므로, 같은 적재 저장에 반영한다.
                    meta = { it.meta },
                ) {
                    chatTurnAiClient.streamTurn(aiRequest) { token ->
                        if (Thread.currentThread().isInterrupted) {
                            return@streamTurn
                        }
                        emitter.send(
                            SseEmitter.event()
                                .name("token")
                                .data(ChatStreamTokenEvent(token)),
                        )
                    }
                }
                succeededAiCallLogId = recorded.aiCallLogId
                val result = recorded.result
                val persisted = chatTurnPersister.persistTurn(
                    playSessionId = sessionId,
                    userInput = request.userInput,
                    aiOutput = result.aiOutput,
                    choices = result.choices,
                )
                Sentry.addBreadcrumb("chat turn persisted: turn=${persisted.turnIndex}", "db")
                // 실제 turn 번호는 persistTurn이 확정하므로, 적재된 호출에 그 값을 채워 정합성을 맞춘다.
                aiCallRecorder.attachTurnIndex(recorded.aiCallLogId, persisted.turnIndex)
                structuredLogger.event(
                    "user_message_saved",
                    "chat_id" to chatId,
                    "story_id" to session.storyId,
                    "turn_index" to persisted.turnIndex,
                    "message_length_bucket" to LengthBuckets.of(request.userInput.length),
                )
                structuredLogger.event(
                    "ai_response_saved",
                    "chat_id" to chatId,
                    "story_id" to session.storyId,
                    "turn_index" to persisted.turnIndex,
                    "ai_call_log_id" to recorded.aiCallLogId,
                )
                emitter.send(
                    SseEmitter.event()
                        .name("completed")
                        .data(
                            ChatStreamCompletedEvent(
                                chatId = chatId,
                                turnId = persisted.turnId,
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
                // AI 호출 자체 실패는 record의 onFailure(captureChatFailure)에서 이미 캡처했다(succeededAiCallLogId == null).
                // record가 성공 반환한 뒤의 실패(예: persistTurn DB·트랜잭션 오류)는 GlobalExceptionHandler를 거치지 않고
                // 여기서 삼켜지므로, 그 호출 id를 참조해 직접 캡처한다.
                succeededAiCallLogId?.let {
                    captureChatFailure(it, exception, session, chatId, attachToAiCallLog = false)
                }
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
     * 시작 설정에 연결된 추천 입력을 input_order 오름차순으로 조회한다.
     * 시작 설정이 없으면(startSettingId == null) 조회 없이 빈 목록을 반환한다.
     */
    private fun loadSuggestedInputs(startSettingId: Long?): List<String> =
        startSettingId
            ?.let { storySuggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(it) }
            ?.map { it.inputText }
            ?: emptyList()

    /**
     * 공개 식별자(UUID 문자열)로 세션을 조회한다. 형식이 잘못됐거나 존재하지 않으면 404로 통일한다.
     * 순차 정수든 임의 문자열이든 동일하게 404를 반환해 존재 여부를 노출하지 않는다.
     */
    private fun resolveSession(publicId: String): StoryPlaySession {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
        return storyPlaySessionRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
    }

    private fun parsePublicIdOrNull(raw: String): UUID? =
        try {
            UUID.fromString(raw)
        } catch (ignored: IllegalArgumentException) {
            null
        }

    /**
     * 세션의 전체 대화 내역(USER+ASSISTANT)을 시간순으로 조립한다.
     * SYSTEM 메시지는 AI history에서 제외한다.
     */
    private fun assembleHistory(chatId: Long): List<ChatHistoryMessage> {
        val all = storyMessageRepository.findByPlaySessionIdOrderByMessageOrderAsc(chatId)
        return all.mapNotNull { message ->
            when (message.role) {
                MessageRole.USER -> ChatHistoryMessage(ChatMessageRole.USER, message.content)
                MessageRole.ASSISTANT -> ChatHistoryMessage(ChatMessageRole.ASSISTANT, message.content)
                MessageRole.SYSTEM -> null
            }
        }
    }

    /**
     * chat_response AI 호출 실패를 Sentry로 보내고 sentry_event_id를 ai_call_logs·로그에 연결한다.
     * SSE는 HTTP 200이라 5xx 필터에 안 잡히므로 여기서 직접 캡처한다.
     * ChatTurnAiException(AI가 의도적으로 내려준 구조화 오류)은 전송 대상이 아니므로 제외한다.
     */
    private fun captureChatFailure(
        aiCallLogId: Long,
        throwable: Throwable,
        session: StoryPlaySession,
        chatId: String,
        attachToAiCallLog: Boolean = true,
    ) {
        if (throwable is ChatTurnAiException) {
            return
        }
        var sentryId = SentryId.EMPTY_ID
        Sentry.withScope { scope ->
            scope.setTag("story_id", session.storyId.toString())
            scope.setTag("chat_id", chatId)
            scope.setContexts(
                "ai",
                mapOf(
                    "feature" to AiCallFeature.CHAT_RESPONSE.value,
                    "ai_call_log_id" to aiCallLogId,
                ),
            )
            sentryId = Sentry.captureException(throwable)
        }
        // AI 호출이 성공한 뒤(저장 등)의 실패는 ai_call_logs 행(SUCCEEDED)을 건드리지 않고 scope·로그에 상관관계만 남긴다.
        if (attachToAiCallLog) {
            aiCallRecorder.attachSentryEventId(aiCallLogId, sentryId.toString())
        }
        structuredLogger.event(
            "ai_stream_failed",
            "chat_id" to chatId,
            "story_id" to session.storyId,
            "ai_call_log_id" to aiCallLogId,
            "sentry_event_id" to sentryId.toString(),
        )
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
