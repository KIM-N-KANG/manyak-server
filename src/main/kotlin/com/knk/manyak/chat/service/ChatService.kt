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
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.observability.LengthBuckets
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import io.sentry.Sentry
import io.sentry.protocol.SentryId
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service
class ChatService(
    @Qualifier("chatSseExecutor")
    private val chatSseExecutor: Executor,
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyChatRepository: StoryChatRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val chatTurnAiClient: ChatTurnAiClient,
    private val chatTurnPersister: ChatTurnPersister,
    private val structuredLogger: StructuredLogger,
    private val aiCallRecorder: AiCallRecorder,
    private val creditWalletService: CreditWalletService,
    // 채팅 턴 1회 소모량(스펙 §4-3-7). 금액은 미확정이라 설정값으로 두고 기본 1을 쓴다.
    @param:Value("\${manyak.credit.chat-turn-cost:1}")
    private val chatTurnCost: Long,
) {

    @Transactional
    fun createChat(request: CreateChatRequest, userId: Long? = null): CreateChatResponse {
        // 스토리 공개 식별자(public_id)로 받아 삭제되지 않은 내부 스토리를 조회한다.
        // 이 한 번의 조회가 KNK-256(public_id 해석)과 KNK-257(삭제된 스토리로 채팅 생성 차단)을 함께 처리한다.
        // 형식 오류·미존재·삭제는 모두 404로 통일된다.
        val story = resolveStory(request.storyId)
        // 시작 설정은 내부 PK(story.id)로 조회한다.
        val startSetting = storyStartSettingRepository.findByStoryId(story.id)

        val chat = storyChatRepository.save(
            StoryChat(
                userId = userId,
                storyId = story.id,
                startSettingId = startSetting?.id,
            ),
        )
        structuredLogger.event(
            "chat_started",
            // 로그의 story_id는 story_created·분석 이벤트와 조인되도록 공개 식별자(public UUID)로 남긴다.
            "story_id" to story.publicId.toString(),
            "chat_id" to chat.publicId.toString(),
        )

        val suggestedInputs = loadSuggestedInputs(startSetting?.id)

        return CreateChatResponse(
            id = chat.publicId.toString(),
            storyId = story.publicId.toString(),
            prologue = startSetting?.prologue.orEmpty(),
            suggestedInputs = suggestedInputs,
            createdAt = chat.createdAt,
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
        val chats = storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(requestedPublicIds)
            .sortedWith(compareByDescending<StoryChat> { it.updatedAt }.thenByDescending { it.id })
        return toSummaryResponses(chats)
    }

    /**
     * 회원 서재(KNK-447): 요청자가 소유한 채팅 카드를 최근 활동순(updatedAt)으로 반환한다. 소프트 삭제는 제외한다.
     * 카드 스키마는 [getChatsByIds](/chats/batch)와 동일하다([toSummaryResponses]).
     */
    @Transactional(readOnly = true)
    fun getMyChats(userId: Long, limit: Int): List<ChatSummaryResponse> =
        toSummaryResponses(
            storyChatRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(userId, PageRequest.of(0, limit)),
        )

    /**
     * 채팅 목록을 카드 응답으로 매핑한다. 스토리 제목·마지막 프리뷰를 각각 한 번의 배치 조회로 채운다(N+1 방지).
     * 입력 순서를 그대로 보존하므로, 정렬은 호출부(요청 순서·최근 활동순)에서 결정한다.
     */
    private fun toSummaryResponses(chats: List<StoryChat>): List<ChatSummaryResponse> {
        if (chats.isEmpty()) {
            return emptyList()
        }
        // 응답에 스토리 공개 식별자(public_id)와 제목을 노출하기 위해 스토리를 한 번에 조회해 매핑한다.
        val storiesByStoryId = storyRepository.findAllById(chats.map { it.storyId })
            .associateBy { it.id }
        // 채팅별 마지막 ASSISTANT 메시지만 한 번의 쿼리로 조회해 프리뷰로 사용한다.
        val lastPreviewByChatId = storyMessageRepository
            .findLatestMessagesByChatIdsAndRole(chats.map { it.id }, MessageRole.ASSISTANT)
            .associate { it.chatId to it.content }
        return chats.map { chat ->
            val story = storiesByStoryId[chat.storyId]
            ChatSummaryResponse(
                id = chat.publicId.toString(),
                storyId = story?.publicId?.toString().orEmpty(),
                storyTitle = story?.title.orEmpty(),
                lastStoryPreview = lastPreviewByChatId[chat.id].orEmpty(),
                // 턴 수는 persistTurn이 턴 저장과 원자적으로 증가시키는 비정규화 카운터를 그대로 읽는다.
                turnCount = chat.currentTurn,
                updatedAt = chat.updatedAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getChatDetail(chatId: String): ChatDetailResponse {
        val chat = resolveChat(chatId)

        val story = storyRepository.findById(chat.storyId).orElse(null)
        val storyTitle = story?.title.orEmpty()
        // prologue와 추천 입력 모두 시작 설정에 종속되므로 한 번만 조회해 재사용한다.
        val startSetting = storyStartSettingRepository.findByStoryId(chat.storyId)

        val messages = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)
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
            id = chat.publicId.toString(),
            storyId = story?.publicId?.toString().orEmpty(),
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
        val chat = resolveChat(chatId)
        chat.deletedAt = Instant.now()
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
     * 채팅 검증과 AI 요청 재료 조립은 동기로 끝내 잘못된 요청은 즉시 404/400으로 응답하고,
     * 실제 토큰 스트리밍과 저장은 [chatSseExecutor] 위에서 비동기로 처리한다.
     * 스트리밍 동안에는 트랜잭션을 점유하지 않고, 저장은 completed 시점에 [ChatTurnPersister]가
     * 단일 트랜잭션으로 원자적으로 수행한다.
     *
     * 크레딧(스펙 §4-3-7 소모): 회원([userId] != null)이면 SSE를 열기 **전에** 동기로 1턴분을 선차감한다.
     * 잔액이 부족하면 [com.knk.manyak.credit.InsufficientCreditException]이 여기서 동기로 던져져
     * 컨트롤러가 스트림을 열기 전에 402로 변환한다(스트림 안 error 이벤트가 아님). 게스트는 차감하지 않는다.
     * 턴이 completed 없이 끝나면(error·연결 끊김·미완료) 선차감분을 전액 환불한다(정확히 1회, 멱등 키로 이중 방어).
     */
    fun streamChatTurn(
        chatId: String,
        request: ContinueChatRequest,
        userId: Long? = null,
    ): SseEmitter {
        // 채팅을 공개 식별자로 먼저 검증한다(없으면 동기 404). 이후 내부 PK로 저장·이력을 처리하고,
        // SSE 이벤트에는 외부에 노출하는 공개 식별자(chatId)만 싣는다.
        val chat = resolveChat(chatId)
        val chatPk = chat.id
        // 소유권 강제(스펙 §4-5): 회원 소유 채팅(userId != null)은 소유자만 이어쓸 수 있다.
        // 토큰 누락·만료(요청 userId == null)나 타 회원이 owned 채팅에 이어쓰면 403으로 막는다.
        // 이 검사가 없으면 토큰을 빼 게스트로 위장해 소유 채팅을 무료로 이어써 선차감을 우회할 수 있다(Codex P1).
        // 게스트 채팅(chat.userId == null)은 익명 허용이며 차감이 없다(기존 동작 유지).
        if (chat.userId != null && chat.userId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 이어쓸 권한이 없습니다.")
        }
        // 스토리는 프롬프트 조립(장르)과 로그·Sentry의 공개 식별자에 함께 쓰므로 한 번만 조회한다.
        val story = storyRepository.findById(chat.storyId).orElse(null)
        val storyPublicId = story?.publicId?.toString().orEmpty()
        val aiRequest = assembleAiRequest(chat, story, request.userInput)

        // 회원이면 SseEmitter를 만들기 전에 동기로 선차감한다. 잔액 부족 시 여기서 InsufficientCreditException이
        // 던져져(스트림 미개시) 컨트롤러가 402로 변환한다. 게스트(null)는 차감하지 않는다.
        // 위 소유권 가드 뒤이므로, owned 채팅이면 요청자 == 소유자 ⇒ 소유자가 차감되고,
        // 게스트 채팅을 회원이 이어쓰면 그 회원이 차감된다(게스트는 userId == null이라 무차감).
        if (userId != null) {
            creditWalletService.deduct(userId, chatTurnCost, CreditReason.CHAT_TURN, refType = "CHAT", refId = chatPk)
        }
        // 환불 상태: 이 턴 시도의 결정적 멱등 키(재시도·이중 콜백에도 환불 1회 보장)와, 실행은 최초 1회만 하는 게이트.
        // persisted는 "턴이 저장돼 차감이 확정됨"을 뜻한다. persistTurn 성공 직후 세워, 이후 completed 전송 실패·연결
        // 끊김이 있어도 환불하지 않는다(저장된 턴은 이력에 남아 무료로 재조회되면 안 됨, Codex P1). 저장 전 실패만 환불한다.
        val refundKey = "refund:chatturn:${UUID.randomUUID()}"
        val refundGate = AtomicBoolean(false)
        val persisted = AtomicBoolean(false)

        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)
        val futureRef = AtomicReference<CompletableFuture<Void>>()

        emitter.onTimeout {
            futureRef.get()?.cancel(true)
            emitter.complete()
        }
        emitter.onCompletion {
            futureRef.get()?.cancel(true)
            // 안전망: 턴이 저장되지 않은 채 끝난 모든 종료(저장 전 error·연결 끊김·타임아웃)에서만 선차감분을 환불한다.
            // 저장된 턴(persisted)은 차감이 확정이라 건너뛰고, 아래 catch의 즉시 환불과는 게이트·멱등 키로 이중 실행되지 않는다.
            if (!persisted.get()) {
                refundChatTurn(userId, chatPk, refundKey, refundGate)
            }
        }
        emitter.onError {
            futureRef.get()?.cancel(true)
        }

        val future = try {
            CompletableFuture.runAsync({
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
                // turn_number는 persistTurn이 DB에서 확정한 뒤 attachTurnNumber로 채운다(동시 요청 정합성).
                val recorded = aiCallRecorder.record(
                    AiCallContext(
                        feature = AiCallFeature.CHAT_RESPONSE,
                        storyId = chat.storyId,
                        chatId = chat.publicId,
                    ),
                    errorCode = { throwable ->
                        if (throwable is ChatTurnAiException) throwable.code else "AI_STREAM_FAILED"
                    },
                    onFailure = { aiCallLogId, throwable ->
                        captureChatFailure(aiCallLogId, throwable, storyPublicId, chatId)
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
                val persistedTurn = chatTurnPersister.persistTurn(
                    chatId = chatPk,
                    userInput = request.userInput,
                    aiOutput = result.aiOutput,
                    choices = result.choices,
                )
                // 저장이 확정된 순간 차감을 굳힌다(completed 전송 전). 이후 completed 전송이 실패하거나 클라이언트가
                // 끊겨도 환불하지 않는다 — 저장된 턴은 이력에 남아 회원이 재조회로 볼 수 있으므로 과금이 정당하다(Codex P1).
                persisted.set(true)
                Sentry.addBreadcrumb("chat turn persisted: turn=${persistedTurn.turnNumber}", "db")
                // 실제 turn 번호는 persistTurn이 확정하므로, 적재된 호출에 그 값을 채워 정합성을 맞춘다.
                aiCallRecorder.attachTurnNumber(recorded.aiCallLogId, persistedTurn.turnNumber)
                structuredLogger.event(
                    "user_message_saved",
                    "chat_id" to chatId,
                    "story_id" to storyPublicId,
                    "turn_number" to persistedTurn.turnNumber,
                    "message_length_bucket" to LengthBuckets.of(request.userInput.length),
                )
                structuredLogger.event(
                    "ai_response_saved",
                    "chat_id" to chatId,
                    "story_id" to storyPublicId,
                    "turn_number" to persistedTurn.turnNumber,
                    "ai_call_log_id" to recorded.aiCallLogId,
                )
                emitter.send(
                    SseEmitter.event()
                        .name("completed")
                        .data(
                            ChatStreamCompletedEvent(
                                chatId = chatId,
                                turnId = persistedTurn.turnId,
                                aiOutput = result.aiOutput,
                                choices = result.choices,
                            ),
                        ),
                )
                emitter.complete()
            } catch (exception: ChatTurnAiException) {
                // AI가 내려준 구조화 오류는 code·message를 그대로 relay한다. 저장 전 실패이므로 선차감분을 환불한다.
                if (!persisted.get()) {
                    refundChatTurn(userId, chatPk, refundKey, refundGate)
                }
                sendErrorQuietly(emitter, exception.code, exception.message)
                emitter.complete()
            } catch (exception: Exception) {
                // AI 호출 자체 실패는 record의 onFailure(captureChatFailure)에서 이미 캡처했다(succeededAiCallLogId == null).
                // record가 성공 반환한 뒤의 실패(예: persistTurn DB·트랜잭션 오류, completed 전송 실패)는 GlobalExceptionHandler를
                // 거치지 않고 여기서 삼켜지므로, 그 호출 id를 참조해 직접 캡처한다.
                succeededAiCallLogId?.let {
                    captureChatFailure(it, exception, storyPublicId, chatId, attachToAiCallLog = false)
                }
                // 저장 전 실패만 환불한다. 저장이 확정된 뒤의 실패(completed 전송 실패·연결 끊김)는 환불하지 않는다 —
                // 저장된 턴은 이력에 남아 회원이 재조회로 볼 수 있으므로 과금이 정당하다(Codex P1).
                if (!persisted.get()) {
                    refundChatTurn(userId, chatPk, refundKey, refundGate)
                }
                sendErrorQuietly(emitter, "AI_STREAM_FAILED", "AI 응답 생성 중 오류가 발생했습니다.")
                emitter.complete()
            }
        }, chatSseExecutor)
        } catch (rejected: Throwable) {
            // 스케줄 거부(chatSseExecutor 포화 시 RejectedExecutionException 등)는 위 async 블록이 실행되지 않아
            // 그 catch·onCompletion 환불이 돌지 않는다. 이미 선차감했으므로 여기서 환불한 뒤(gate로 1회) 예외를
            // 그대로 올려 호출자에게 실패로 드러낸다. 스트림은 열리지 않았으니 emitter를 오류로 닫아 반쯤 열린 상태를 막는다(Codex P1).
            refundChatTurn(userId, chatPk, refundKey, refundGate)
            runCatching { emitter.completeWithError(rejected) }
            structuredLogger.event(
                "chat_turn_schedule_rejected",
                "chat_id" to chatId,
                "story_id" to storyPublicId,
                "error" to (rejected.message ?: rejected::class.simpleName ?: "unknown"),
            )
            throw rejected
        }
        futureRef.set(future)

        return emitter
    }

    /**
     * 실패·미완료 턴의 선차감분을 환불한다. [userId]가 null(게스트)이면 아무것도 하지 않는다.
     *
     * [gate]로 최초 1회만 실행하고, [reward]의 멱등 키([refundKey])로 한 번 더 방어한다 — 즉시 환불(catch)과
     * 안전망(onCompletion)이 겹쳐도 원장에 REFUND 행은 정확히 1건만 남는다. 환불 실패가 SSE 종료를 막지 않도록
     * 예외는 삼키고 로그만 남긴다(선차감이 원장에 있어 사후 정산·재시도로 복구 가능).
     */
    private fun refundChatTurn(userId: Long?, chatPk: Long, refundKey: String, gate: AtomicBoolean) {
        if (userId == null) return
        if (!gate.compareAndSet(false, true)) return
        try {
            creditWalletService.reward(
                userId = userId,
                amount = chatTurnCost,
                reason = CreditReason.REFUND,
                idempotencyKey = refundKey,
                refType = "CHAT",
                refId = chatPk,
            )
        } catch (exception: Exception) {
            structuredLogger.event(
                "chat_turn_refund_failed",
                "chat_pk" to chatPk,
                "idempotency_key" to refundKey,
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
    }

    /**
     * 이미 검증된 채팅으로 AI 채팅 턴 요청 재료를 조립한다.
     * 오프닝은 [ChatTurnStartSettings]로만 전달하고 history에는 포함하지 않으며,
     * 현재 입력은 아직 저장 전이므로 history에 들어가지 않는다.
     */
    private fun assembleAiRequest(chat: StoryChat, story: Story?, userInput: String): ChatTurnAiRequest {
        val genre = story?.genre.orEmpty()
        val setting = storySettingRepository.findByStoryId(chat.storyId)
        val startSetting = storyStartSettingRepository.findByStoryId(chat.storyId)

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
            history = assembleHistory(chat.id),
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
     * 공개 식별자(UUID 문자열)로 채팅을 조회한다. 형식이 잘못됐거나 존재하지 않으면 404로 통일한다.
     * 순차 정수든 임의 문자열이든 동일하게 404를 반환해 존재 여부를 노출하지 않는다.
     */
    private fun resolveChat(publicId: String): StoryChat {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
        return storyChatRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
    }

    private fun parsePublicIdOrNull(raw: String): UUID? =
        try {
            UUID.fromString(raw)
        } catch (ignored: IllegalArgumentException) {
            null
        }

    /**
     * 스토리 공개 식별자(UUID 문자열)로 삭제되지 않은 스토리를 조회한다. 형식 오류·미존재·삭제는 404로 통일한다.
     * createChat이 KNK-256(public_id 해석)과 KNK-257(삭제 스토리 채팅 차단)을 한 조회로 처리하게 한다.
     */
    private fun resolveStory(publicId: String): Story {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        return storyRepository.findByPublicIdAndDeletedAtIsNull(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
    }

    /**
     * 채팅의 전체 대화 내역(USER+ASSISTANT)을 시간순으로 조립한다.
     * SYSTEM 메시지는 AI history에서 제외한다.
     */
    private fun assembleHistory(chatId: Long): List<ChatHistoryMessage> {
        val all = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chatId)
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
        storyPublicId: String,
        chatId: String,
        attachToAiCallLog: Boolean = true,
    ) {
        if (throwable is ChatTurnAiException) {
            return
        }
        var sentryId = SentryId.EMPTY_ID
        Sentry.withScope { scope ->
            scope.setTag("story_id", storyPublicId)
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
            "story_id" to storyPublicId,
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
