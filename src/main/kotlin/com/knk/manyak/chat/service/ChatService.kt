package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatHistoryMessage
import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.client.ChatTurnEnding
import com.knk.manyak.chat.client.ChatTurnMainEvent
import com.knk.manyak.chat.client.ChatTurnStartSettings
import com.knk.manyak.chat.client.ChatTurnStorySettings
import com.knk.manyak.chat.client.ChatTurnTargetMainEvent
import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatChoicesResponse
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
import com.knk.manyak.chat.dto.RegenerateChatRequest
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.repository.StoryChatMainEventRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.LengthBuckets
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.global.security.isOwnerAccessAllowed
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
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
    private val imageUrlResolver: ImageUrlResolver,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyChatMainEventRepository: StoryChatMainEventRepository,
    private val storyChatRepository: StoryChatRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val chatTurnAiClient: ChatTurnAiClient,
    private val chatTurnPersister: ChatTurnPersister,
    private val structuredLogger: StructuredLogger,
    private val aiCallRecorder: AiCallRecorder,
    private val creditWalletService: CreditWalletService,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val suspensionGuard: SuspensionGuard,
    private val serverAnalytics: ServerAnalytics,
    // 채팅 턴 1회 소모량(스펙 §4-3-7, KNK-477 확정: 10. 재생성도 동일 값·사유를 공유).
    @param:Value("\${manyak.credit.chat-turn-cost:10}")
    private val chatTurnCost: Long,
) {

    @Transactional
    fun createChat(request: CreateChatRequest, userId: Long? = null): CreateChatResponse {
        // 스토리 공개 식별자(public_id)로 받아 삭제되지 않은 내부 스토리를 조회한다.
        // 이 한 번의 조회가 KNK-256(public_id 해석)과 KNK-257(삭제된 스토리로 채팅 생성 차단)을 함께 처리한다.
        // 형식 오류·미존재·삭제는 모두 404로 통일된다.
        val story = resolveStory(request.storyId)
        // 공개(PUBLISHED∧PUBLIC) 스토리이거나 소유자만 채팅을 시작할 수 있다(KNK-401). 비공개·초안은 소유자 외엔 404.
        if (!story.isReadableBy(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        // 교차 접근 차단(§4-5, KNK-480): 게스트 소유(NULL) 스토리에는 인증 회원이 채팅을 시작할 수 없다(이관 후 접근).
        // 소유자 있는(본인·공개 발행) 스토리 채팅 생성은 영향받지 않는다.
        if (story.userId == null && userId != null) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "이 스토리로는 채팅을 시작할 수 없습니다.")
        }
        // 시작 설정 복수화(KNK-515): startSettingId를 지정하면 그 시작 설정으로, 미지정이면 스토리의 첫(기본) 시작 설정으로 시작한다.
        // 지정 값이 형식 오류거나 이 스토리에 속하지 않으면 404로 통일한다(존재 노출 최소화·조용한 폴백 금지).
        val startSetting = resolveStartSetting(story.id, request.startSettingId)

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
    fun getChatsByIds(request: BatchChatRequest, userId: Long?): List<ChatSummaryResponse> {
        // 공개 식별자(UUID 문자열)로 받는다. 형식이 잘못된 값은 매칭될 수 없으므로 조용히 제외한다.
        val requestedPublicIds = request.chatIds.mapNotNull { parsePublicIdOrNull(it) }
        // 유효한 식별자가 하나도 없으면 DB 조회 없이 즉시 빈 목록을 반환한다.
        if (requestedPublicIds.isEmpty()) {
            return emptyList()
        }
        // 존재하고 삭제되지 않은 채팅만 마지막 진행 시각(updatedAt) 내림차순으로 노출한다.
        // updatedAt이 같으면 id 내림차순으로 결정적 순서를 보장한다. 존재하지 않거나 형식이
        // 잘못된 채팅 ID는 조회되지 않으므로 자연히 제외된다.
        // 열람 규칙(스펙 §4-5 B16): 요청자가 열람할 수 없는 채팅(회원 요청의 NULL 채팅·타인 소유)은
        // 배치 조회 계약상 항목 존재를 드러내지 않으므로 403이 아니라 결과에서 조용히 제외한다.
        val chats = storyChatRepository.findAllByPublicIdInAndDeletedAtIsNull(requestedPublicIds)
            .filter { isOwnerAccessAllowed(it.userId, userId) }
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
        // 채팅이 도달한 엔딩 이름을 한 번에 조회한다(엔딩은 이름으로 노출, KNK-462). 채팅당 최대 1개.
        val endingNameById = storyEndingRepository
            .findAllById(chats.mapNotNull { it.reachedEndingId })
            .associate { it.id to it.name }
        return chats.map { chat ->
            val story = storiesByStoryId[chat.storyId]
            ChatSummaryResponse(
                id = chat.publicId.toString(),
                storyId = story?.publicId?.toString().orEmpty(),
                storyTitle = story?.title.orEmpty(),
                // 채팅 카드(46×62)도 목록과 같은 축소 변형을 공유한다(스펙 §4-3-9 반응형 변형).
                thumbnailUrlSm = imageUrlResolver.thumbnailSmUrlFor(story?.thumbnailImageKey),
                lastStoryPreview = lastPreviewByChatId[chat.id].orEmpty(),
                // 턴 수는 persistTurn이 턴 저장과 원자적으로 증가시키는 비정규화 카운터를 그대로 읽는다.
                turnCount = chat.currentTurn,
                reachedEndings = chat.reachedEndingId?.let { id -> endingNameById[id]?.let(::listOf) }.orEmpty(),
                updatedAt = chat.updatedAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getChatDetail(chatId: String, userId: Long?): ChatDetailResponse {
        val chat = resolveChat(chatId)
        // 소유권 게이트(§4-5, KNK-480): 소유 채팅은 소유자만, 게스트 채팅은 게스트만 조회한다.
        // 존재 판정(resolveChat의 404) 뒤에 적용해, 회원의 게스트 채팅 열람·타인 소유 채팅 열람을 403으로 막는다.
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 조회할 권한이 없습니다.")
        }

        val story = storyRepository.findById(chat.storyId).orElse(null)
        val storyTitle = story?.title.orEmpty()
        // prologue와 추천 입력 모두 시작 설정에 종속되므로 한 번만 조회해 재사용한다.
        val startSetting = chat.startSettingId?.let { storyStartSettingRepository.findById(it).orElse(null) }

        val messages = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id)
        val turns = pairTurns(messages)

        val choicesByMessageId = if (turns.isEmpty()) {
            emptyMap()
        } else {
            storyChoiceRepository.findByMessageIdInOrderByChoiceOrderAsc(turns.map { it.id })
                .groupBy { it.messageId }
                .mapValues { (_, choices) -> choices.map { it.choiceText } }
        }

        // 도달 엔딩은 이름으로 노출한다(순차 PK 노출 금지, KNK-462). 이 상세에 등장하는 도달 턴들의 엔딩 id를 한 번에
        // 조회해 이름으로 매핑한다(toSummaryResponses와 동일 패턴, N+1 방지). 도달 턴이 없으면 조회를 생략한다.
        val endingNameById = storyEndingRepository
            .findAllById(turns.mapNotNull { it.reachedEndingId })
            .associate { it.id to it.name }

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
                    reachedEnding = assistant.reachedEndingId?.let { endingNameById[it] },
                    createdAt = assistant.createdAt,
                )
            },
            suggestedInputs = suggestedInputs,
        )
    }

    /**
     * 채팅을 소프트 삭제한다. 행을 물리 삭제하지 않고 deletedAt만 기록해 자식 데이터(메시지·선택지)를 보존한다.
     * 이미 삭제됐거나 존재하지 않으면(순차 정수·임의 값 포함) 404로 통일한다.
     * 존재 여부를 노출하지 않도록 소유권 403은 404(없음·이미 삭제) 판정 뒤에 적용한다.
     */
    @Transactional
    fun deleteChat(chatId: String, userId: Long?) {
        // 영속 상태 엔티티의 변경은 트랜잭션 커밋 시 더티 체킹으로 반영된다(명시적 save 불필요).
        // 소유권 검사와 deletedAt 기록 사이에 마이그레이션 클레임이 끼어드는 경쟁을 막으려 행에 비관적 쓰기 락을 건다(KNK-69).
        val chat = resolveChatForUpdate(chatId)
        // 소유권 게이트(§4-5, KNK-480): 게스트 채팅은 게스트만, 소유 채팅은 소유자만. 회원의 NULL 소유 채팅 삭제도 차단. 위반 시 403.
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 삭제할 권한이 없습니다.")
        }
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
                            // 도달 엔딩은 ASSISTANT 메시지에 표식된다(reached_ending_id). 상세에서 이름으로 해소한다.
                            reachedEndingId = message.reachedEndingId,
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
        val reachedEndingId: Long?,
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
     * 컨트롤러가 스트림을 열기 전에 402로 변환한다(스트림 안 error 이벤트가 아님). 게스트는 크레딧 대신
     * 디바이스 ID별 chat_turn 체험 한도를 예약하며(KNK-477), 한도 소진·device 헤더 누락은 동기 402/400이다.
     * 턴이 completed 없이 끝나면(error·연결 끊김·미완료) 선차감분·예약분을 전액 환불·복원한다(정확히 1회, 멱등 키로 이중 방어).
     */
    fun streamChatTurn(
        chatId: String,
        request: ContinueChatRequest,
        userId: Long? = null,
        deviceId: String? = null,
    ): SseEmitter {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        // 채팅을 공개 식별자로 먼저 검증한다(없으면 동기 404). 이후 내부 PK로 저장·이력을 처리하고,
        // SSE 이벤트에는 외부에 노출하는 공개 식별자(chatId)만 싣는다.
        val chat = resolveChat(chatId)
        // 소유권 강제(스펙 §4-5): 회원 소유 채팅(userId != null)은 소유자만 이어쓸 수 있다.
        // 토큰 누락·만료(요청 userId == null)나 타 회원이 owned 채팅에 이어쓰면 403으로 막는다.
        // 이 검사가 없으면 토큰을 빼 게스트로 위장해 소유 채팅을 무료로 이어써 선차감을 우회할 수 있다(Codex P1).
        // 게스트 채팅(chat.userId == null)은 게스트(요청 userId == null)만 허용하며 차감이 없다(회원은 이관 후 접근, KNK-480).
        requireChatOwner(chat, userId)
        // 스토리는 프롬프트 조립(장르)과 로그·Sentry의 공개 식별자에 함께 쓰므로 한 번만 조회한다.
        val story = storyRepository.findById(chat.storyId).orElse(null)
        val storyPublicId = story?.publicId?.toString().orEmpty()
        val aiRequest = assembleAiRequest(chat, story, request.userInput)

        return streamTurnInternal(
            chatId = chatId,
            chat = chat,
            storyPublicId = storyPublicId,
            aiRequest = aiRequest,
            userId = userId,
            deviceId = deviceId,
            isRegenerated = false,
            persist = { result ->
                chatTurnPersister.persistTurn(
                    chatId = chat.id,
                    userInput = request.userInput,
                    aiOutput = result.aiOutput,
                    choices = result.choices,
                    judgment = result.toTurnJudgment(),
                )
            },
            onPersisted = { persistedTurn, aiCallLogId ->
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
                    "ai_call_log_id" to aiCallLogId,
                )
            },
        )
    }

    /**
     * 마지막 턴의 AI 출력을 같은 사용자 입력으로 다시 생성해 교체한다(재생성, 스펙 §4-3-9). 이어쓰기와 동일 SSE 계약이다.
     *
     * 이어쓰기와 다른 점: (1) 새 USER 메시지를 만들지 않고 마지막 USER 입력을 그대로 재전송한다. (2) history에서 마지막 턴
     * (USER·ASSISTANT 쌍)을 제외한다(1..N-1). (3) completed 시 새 턴을 추가하지 않고 마지막 ASSISTANT 본문·선택지만 교체하며
     * current_turn을 늘리지 않는다. 요청 [RegenerateChatRequest.turnId]가 서버의 마지막 턴과 다르면 동기 409, 턴이 0개면 404.
     * 엔딩에 도달한 채팅(status ENDED)은 재생성 대상에서 제외해 동기 409로 막는다(§4-3-10 도달 기록 확정 후이므로).
     * 크레딧은 이어쓰기와 동일하게 1턴분을 선차감하고, 저장(교체) 전 실패·마지막 턴 변경 시 환불한다(§4-3-7).
     */
    fun regenerateChatTurn(
        chatId: String,
        request: RegenerateChatRequest,
        userId: Long? = null,
        deviceId: String? = null,
    ): SseEmitter {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        val chat = resolveChat(chatId)
        requireChatOwner(chat, userId)
        // 엔딩 도달 턴은 재생성 대상이 아니다(§4-3-10 도달 기록 확정 후). 도달 기록이 채팅을 ENDED로 굳히므로 여기서 막는다.
        if (chat.status == ChatStatus.ENDED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "엔딩에 도달한 채팅은 재생성할 수 없습니다.")
        }
        val story = storyRepository.findById(chat.storyId).orElse(null)
        val storyPublicId = story?.publicId?.toString().orEmpty()
        // 마지막 턴 검증(0개면 404, 낡은 turnId면 409)과 재생성용 history(마지막 턴 제외)·재전송 입력을 함께 확정한다.
        val target = resolveRegenerateTarget(chat.id, request.turnId)
        // 재생성 요청은 현재 채팅 상태(목표·거쳐온 사건·엔딩 후보)를 그대로 싣고, 판정 메타는 재적용하지 않는다
        // (regenerateLastTurn은 judgment를 받지 않음). 스펙 §4-3-9/§4-3-10의 "직전 턴까지 상태로 재구성 후 새 메타로
        // 재기록"은 턴별 메타 델타 이력이 있어야 정확한데 현 스키마엔 없어 Phase 1 범위 밖으로 둔다. 엔딩 도달 턴은 위에서
        // 409로 막으므로 도달 불변식(채팅당 최초 1회)은 유지된다.
        val aiRequest = buildAiRequest(chat, story, target.history, target.userInput)

        return streamTurnInternal(
            chatId = chatId,
            chat = chat,
            storyPublicId = storyPublicId,
            aiRequest = aiRequest,
            userId = userId,
            deviceId = deviceId,
            isRegenerated = true,
            persist = { result ->
                chatTurnPersister.regenerateLastTurn(
                    chatId = chat.id,
                    expectedAssistantId = target.assistantId,
                    aiOutput = result.aiOutput,
                    choices = result.choices,
                )
            },
            onPersisted = { persistedTurn, aiCallLogId ->
                structuredLogger.event(
                    "ai_response_regenerated",
                    "chat_id" to chatId,
                    "story_id" to storyPublicId,
                    "turn_number" to persistedTurn.turnNumber,
                    "ai_call_log_id" to aiCallLogId,
                )
            },
        )
    }

    /**
     * 마지막 턴의 다음 행동 선택지 3개를 생성해 저장한다(선택지 분리, 스펙 §4-3-3). 이어쓰기·재생성과 달리 SSE가 아닌 동기이고,
     * 선택지 생성은 **무료**(크레딧·게스트 채팅 한도 미소모)다. 소유 게이트는 이어쓰기와 동형이다.
     *
     * `turnId`가 마지막 턴이 아니면 409(재생성과 동일 패턴), 이미 선택지가 있으면 AI 호출 없이 기존 값을 반환한다(멱등 —
     * 중복 탭·재진입 안전). 프론트는 응답 본문이 아니라 채팅 상세 재조회의 `turns[].choices`로 렌더하나, 응답에도 담아 둔다.
     */
    fun generateChoices(chatId: String, turnId: Long, userId: Long? = null): ChatChoicesResponse {
        suspensionGuard.requireActive(userId) // 정지 계정의 AI 호출·쓰기 차단(§4-5 B20). 선택지는 무료지만 AI 비용은 발생한다.
        val chat = resolveChat(chatId)
        requireChatOwner(chat, userId)

        val story = storyRepository.findById(chat.storyId).orElse(null)
        // 마지막 턴 검증(0개면 404, 낡은·타 채팅 turnId면 409) + 이번 턴 제외 history·재전송 입력·저장 본문을 함께 확정한다.
        // 이 검증을 멱등 사전 검사보다 **먼저** 한다 — messageId로 먼저 조회하면 타 채팅의 turnId로 남의 선택지를 받거나
        // 같은 채팅의 비마지막 턴이 409 대신 200이 될 수 있다(Codex P1 IDOR).
        val target = resolveRegenerateTarget(chat.id, turnId)

        // 멱등: 검증된 마지막 턴에 이미 선택지가 있으면 AI 없이 반환한다. 최종 방어는 fillChoices의 락 안 재검사.
        val existing = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(turnId)
        if (existing.isNotEmpty()) {
            return ChatChoicesResponse(existing.map { it.choiceText })
        }

        val aiRequest = buildAiRequest(chat, story, target.history, target.userInput)

        val recorded = try {
            aiCallRecorder.record(
                AiCallContext(
                    feature = AiCallFeature.CHOICE_GENERATION,
                    storyId = chat.storyId,
                    chatId = chat.publicId,
                ),
                errorCode = { throwable -> if (throwable is ChatTurnAiException) throwable.code else "AI_CHOICE_FAILED" },
                meta = { it.meta },
            ) {
                chatTurnAiClient.generateChoices(aiRequest, target.aiOutput)
            }
        } catch (exception: Exception) {
            // 선택지 생성 실패는 502로 올려 프론트가 재시도한다(본문·판정은 이미 저장돼 있어 영향 없음, 스펙 §4-3-3).
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 선택지 생성 요청에 실패했습니다.", exception)
        }

        val choices = recorded.result.choices
        val turnNumber = chatTurnPersister.fillChoices(chat.id, turnId, choices)
        // ai_call_logs.turn_number를 채워 chat_response 행과 chat_id + turn_number로 조인되게 한다(§4-7).
        aiCallRecorder.attachTurnNumber(recorded.aiCallLogId, turnNumber)
        return ChatChoicesResponse(choices)
    }

    /**
     * 소유권 강제(스펙 §4-5, KNK-480): 회원 소유 채팅(userId != null)은 소유자만 이어쓰기·재생성할 수 있고,
     * 게스트 채팅(chat.userId == null)은 게스트(요청 userId == null)만 허용한다(인증 회원은 차단 — 이관 후 접근).
     */
    private fun requireChatOwner(chat: StoryChat, userId: Long?) {
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 이어쓸 권한이 없습니다.")
        }
    }

    /**
     * 재생성 대상(마지막 턴)을 검증·확정한다.
     *
     * 마지막 ASSISTANT가 없으면(턴 0개) 404, [requestedTurnId]가 서버의 마지막 턴 id와 다르면(낡은 값·중간에 진행됨) 409.
     * history는 마지막 턴(USER·ASSISTANT 쌍)을 제외한 1..N-1로 구성하고(SYSTEM 제외), userInput은 마지막 USER 입력을 그대로 둔다.
     */
    private fun resolveRegenerateTarget(chatPk: Long, requestedTurnId: Long): RegenerateTarget {
        val all = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chatPk)
        // 완료된 마지막 턴 = 가장 큰 messageOrder의 ASSISTANT. 없으면 재생성할 턴이 없다(턴 0개).
        val lastAssistant = all.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "재생성할 턴이 없습니다.")
        // 낙관적 동시성: 클라이언트가 본 마지막 턴과 서버의 마지막 턴이 다르면(그새 이어쓰기로 진행됨 등) 409.
        if (lastAssistant.id != requestedTurnId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "마지막 턴이 아니거나 이미 변경되었습니다.")
        }
        // 마지막 턴의 USER 입력(마지막 ASSISTANT 직전 USER). 그대로 재전송한다(AI 서버는 무상태).
        val pairedUser = all.lastOrNull { it.role == MessageRole.USER && it.messageOrder < lastAssistant.messageOrder }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "재생성할 턴의 사용자 입력을 찾을 수 없습니다.")
        // history: 마지막 턴(USER·ASSISTANT)을 제외한 이전 내역만. SYSTEM은 제외한다.
        val history = all
            .filter { it.messageOrder < pairedUser.messageOrder }
            .mapNotNull { message ->
                when (message.role) {
                    MessageRole.USER -> ChatHistoryMessage(ChatMessageRole.USER, message.content)
                    MessageRole.ASSISTANT -> ChatHistoryMessage(ChatMessageRole.ASSISTANT, message.content)
                    MessageRole.SYSTEM -> null
                }
            }
        return RegenerateTarget(
            assistantId = lastAssistant.id,
            userInput = pairedUser.content,
            aiOutput = lastAssistant.content,
            history = history,
        )
    }

    private data class RegenerateTarget(
        val assistantId: Long,
        val userInput: String,
        // 마지막 턴의 저장된 AI 본문. 선택지 생성 시 ai_output으로 되싣는다(재생성은 사용하지 않음).
        val aiOutput: String,
        val history: List<ChatHistoryMessage>,
    )

    /**
     * 채팅 턴/재생성 공통 SSE 스트리밍. 검증(404/403/409)과 AI 요청 조립은 호출부에서 동기로 끝낸 뒤 여기로 넘긴다.
     *
     * 회원([userId] != null)이면 SseEmitter를 만들기 **전에** 동기로 1턴분을 선차감한다(잔액 부족 시 여기서
     * InsufficientCreditException이 던져져 컨트롤러가 402로 변환, 스트림 미개시). 실제 토큰 스트리밍과 저장은
     * [chatSseExecutor] 위에서 비동기로 처리한다. 저장([persist])이 completed 없이 끝나면(error·연결 끊김·미완료·
     * 마지막 턴 변경) 선차감분을 전액 환불한다(정확히 1회, 멱등 키로 이중 방어).
     *
     * @param persist AI 결과를 원자적으로 저장(이어쓰기: 새 턴 추가 / 재생성: 마지막 턴 교체)하고 저장된 턴을 돌려준다.
     * @param onPersisted 저장 확정 직후의 관측 로그(이벤트 이름·필드가 이어쓰기/재생성마다 달라 호출부가 주입한다).
     */
    private fun streamTurnInternal(
        chatId: String,
        chat: StoryChat,
        storyPublicId: String,
        aiRequest: ChatTurnAiRequest,
        userId: Long?,
        deviceId: String?,
        isRegenerated: Boolean,
        persist: (ChatTurnAiResult) -> ChatTurnPersister.PersistedTurn,
        onPersisted: (ChatTurnPersister.PersistedTurn, Long) -> Unit,
    ): SseEmitter {
        val chatPk = chat.id
        // 회원이면 SseEmitter를 만들기 전에 동기로 선차감한다. 잔액 부족 시 여기서 InsufficientCreditException이
        // 던져져(스트림 미개시) 컨트롤러가 402로 변환한다.
        // 위 소유권 가드 뒤이므로, owned 채팅이면 요청자 == 소유자 ⇒ 소유자가 차감되고,
        // 게스트 채팅을 회원이 이어쓰면 그 회원이 차감된다(게스트는 userId == null이라 무차감).
        // 회원 소모 2단(스펙 §4-3-7 B13): 계정 귀속 체험 잔여가 있으면 먼저 무료로 소진하고, 없으면 크레딧을 선차감한다.
        val memberTrialCovered =
            userId != null && guestTrialLimitService.reserveMember(userId, GuestTrialLimitService.Counter.CHAT_TURN)
        if (userId != null && !memberTrialCovered) {
            creditWalletService.deduct(userId, chatTurnCost, CreditReason.CHAT_TURN, refType = "CHAT", refId = chatPk)
        }
        // 게스트(userId == null)는 크레딧 대신 디바이스 ID별 chat_turn 체험 한도를 예약한다(스펙 §4-3-7, KNK-477).
        // 한도 소진·device 헤더 누락은 여기서 동기 402/400으로 던져져(스트림 미개시) 그대로 컨트롤러에 전파된다.
        val guestDeviceId = guestTrialLimitService.reserveForGuestOrNull(userId, deviceId, GuestTrialLimitService.Counter.CHAT_TURN)
        // 환불 상태: 이 턴 시도의 결정적 멱등 키(재시도·이중 콜백에도 환불 1회 보장)와, 실행은 최초 1회만 하는 게이트.
        // persisted는 "턴이 저장돼 차감이 확정됨"을 뜻한다. persist 성공 직후 세워, 이후 completed 전송 실패·연결
        // 끊김이 있어도 환불하지 않는다(저장된 턴은 이력에 남아 무료로 재조회되면 안 됨, Codex P1). 저장 전 실패만 환불한다.
        val refundKey = "refund:chatturn:${UUID.randomUUID()}"
        val refundGate = AtomicBoolean(false)
        val persisted = AtomicBoolean(false)
        // supplier(워커 본문)가 실제로 실행됐는지 표식. supplier 첫 줄에서 세운다. CompletableFuture.runAsync는
        // 큐 대기 중 cancel되면(result 선점) AsyncRun이 supplier를 통째로 스킵하므로, 이 값이 false면 워커 finally가
        // 영영 돌지 않는다(그 경우 아래 whenComplete가 복원을 맡는다). true면 워커가 실행돼 자기 finally가 복원을 소유한다.
        val workerStarted = AtomicBoolean(false)

        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)
        val futureRef = AtomicReference<CompletableFuture<Void>>()

        emitter.onTimeout {
            futureRef.get()?.cancel(true)
            emitter.complete()
        }
        emitter.onCompletion {
            // 정리만 한다(cancel). 환불은 여기서 판정하지 않는다(Codex P1): 타임아웃 시 onCompletion은 persisted==false로 보지만,
            // cancel(true)가 이미 실행 중인 워커를 중단시키지 못해 워커가 곧이어 persist를 성공시킬 수 있다
            // (AI가 60s 직후 응답). 그러면 환불+저장이 겹쳐 무료 턴이 된다. 그래서 in-flight 환불 판정은 자기 결과를
            // 아는 워커의 finally에 일원화한다(저장 실패 exit ⇒ 환불, 저장 성공 ⇒ 과금). 워커의 AI 호출도 자체 타임아웃이
            // 있어 워커는 반드시 종료하므로, 타임아웃된 턴은 워커가 저장 없이 빠져나갈 때 그 finally에서 환불된다.
            //
            // 큐드-취소(executor 포화로 큐에서 대기하던 태스크가 cancel로 supplier째 스킵되는 경우)는 워커 finally가
            // 아예 돌지 않으므로, 아래 future.whenComplete가 workerStarted==false를 근거로 복원을 맡는다(Codex P1 재리뷰).
            futureRef.get()?.cancel(true)
        }
        emitter.onError {
            futureRef.get()?.cancel(true)
        }

        val future = try {
            CompletableFuture.runAsync({
            // 워커가 실제로 실행됐음을 표식(스킵된 큐드-취소와 구분). 이 뒤로는 finally가 반드시 돌아 복원을 소유한다.
            workerStarted.set(true)
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
                // turn_number는 persist가 DB에서 확정한 뒤 attachTurnNumber로 채운다(동시 요청 정합성).
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
                val persistedTurn = persist(result)
                // 저장이 확정된 순간 차감을 굳힌다(completed 전송 전). 이후 completed 전송이 실패하거나 클라이언트가
                // 끊겨도 환불하지 않는다 — 저장된 턴은 이력에 남아 회원이 재조회로 볼 수 있으므로 과금이 정당하다(Codex P1).
                persisted.set(true)
                Sentry.addBreadcrumb("chat turn persisted: turn=${persistedTurn.turnNumber}", "db")
                // 실제 turn 번호는 persist가 확정하므로, 적재된 호출에 그 값을 채워 정합성을 맞춘다.
                aiCallRecorder.attachTurnNumber(recorded.aiCallLogId, persistedTurn.turnNumber)
                onPersisted(persistedTurn, recorded.aiCallLogId)
                // AI 응답 생성 성공 분석 이벤트(스펙 §6-4-2-6). 엔딩 도달 턴이면 도달 엔딩 id를 함께 싣는다(B5).
                serverAnalytics.chatAiMessageSucceeded(
                    userId = userId,
                    chatId = chatId,
                    turnNumber = persistedTurn.turnNumber,
                    isRegenerated = isRegenerated,
                    endingId = persistedTurn.reachedEnding?.id?.toString(),
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
                                reachedEnding = persistedTurn.reachedEnding?.name,
                            ),
                        ),
                )
                emitter.complete()
            } catch (exception: ChatTurnAiException) {
                // AI가 내려준 구조화 오류는 code·message를 그대로 relay한다. 환불 판정은 아래 finally에 일원화한다.
                sendErrorQuietly(emitter, exception.code, exception.message)
                // AI 응답 생성 실패 분석 이벤트(스펙 §6-4-2-6): 구조화 오류 code를 error_type으로 매핑한다. 저장 전이라 turn_number 미확정.
                serverAnalytics.chatAiMessageFailed(
                    userId = userId,
                    chatId = chatId,
                    turnNumber = null,
                    errorCode = exception.code,
                    isRegenerated = isRegenerated,
                )
                emitter.complete()
            } catch (exception: Exception) {
                // AI 호출 자체 실패는 record의 onFailure(captureChatFailure)에서 이미 캡처했다(succeededAiCallLogId == null).
                // record가 성공 반환한 뒤의 실패(예: persist DB·트랜잭션 오류·마지막 턴 변경, completed 전송 실패)는 GlobalExceptionHandler를
                // 거치지 않고 여기서 삼켜지므로, 그 호출 id를 참조해 직접 캡처한다.
                succeededAiCallLogId?.let {
                    captureChatFailure(it, exception, storyPublicId, chatId, attachToAiCallLog = false)
                }
                sendErrorQuietly(emitter, "AI_STREAM_FAILED", "AI 응답 생성 중 오류가 발생했습니다.")
                // 저장 성공 후 실패(completed 전송 실패 등)는 이미 성공 이벤트를 발행했으므로 중복 실패를 내지 않는다.
                // 저장 전 실패(AI 호출 실패·persist 실패)만 실패 이벤트로 발행한다(스펙 §6-4-2-6).
                if (!persisted.get()) {
                    serverAnalytics.chatAiMessageFailed(
                        userId = userId,
                        chatId = chatId,
                        turnNumber = null,
                        errorCode = "AI_STREAM_FAILED",
                        isRegenerated = isRegenerated,
                    )
                }
                emitter.complete()
            } finally {
                // in-flight 환불 단일 판정: 워커가 저장 없이 빠져나가면(AI 오류·AI 호출 타임아웃·저장 전/저장 실패,
                // 나아가 Error 등 어떤 종료든) 선차감분을 환불한다. 저장에 성공했으면(persisted) 과금을 유지한다.
                // 이 판정을 워커에만 두어 타임아웃-저장 경합을 없앤다(onCompletion은 환불하지 않음, Codex P1). gate로 1회.
                if (!persisted.get()) {
                    refundChatTurn(userId, guestDeviceId, memberTrialCovered, chatPk, refundKey, refundGate)
                }
            }
        }, chatSseExecutor)
        } catch (rejected: Throwable) {
            // 스케줄 거부(chatSseExecutor 포화 시 RejectedExecutionException 등)는 위 async 블록이 실행되지 않아
            // 그 catch·onCompletion 환불이 돌지 않는다. 이미 선차감했으므로 여기서 환불한 뒤(gate로 1회) 예외를
            // 그대로 올려 호출자에게 실패로 드러낸다. 스트림은 열리지 않았으니 emitter를 오류로 닫아 반쯤 열린 상태를 막는다(Codex P1).
            refundChatTurn(userId, guestDeviceId, memberTrialCovered, chatPk, refundKey, refundGate)
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

        // 큐드-취소 안전망(Codex P1 재리뷰): 워커가 실행되지 않은 채(future가 큐 대기 중 cancel돼 supplier가 스킵됨)
        // 종료되면 workerStarted==false다. 이때만 여기서 선차감분을 환불·게스트 카운터를 복원한다. workerStarted==true면
        // 워커가 실행돼 자기 finally가 persisted 여부로 복원을 판정하므로 여기서는 손대지 않는다(환불+저장 겹침 방지).
        // refundGate로 최종 1회만 실행돼, 워커 finally와 겹쳐도 원장·카운터가 이중 복원되지 않는다.
        future.whenComplete { _, _ ->
            if (!workerStarted.get()) {
                refundChatTurn(userId, guestDeviceId, memberTrialCovered, chatPk, refundKey, refundGate)
            }
        }

        return emitter
    }

    /**
     * 실패·미완료 턴의 선차감분을 환불하거나(회원) 예약한 게스트 체험 한도를 복원한다(게스트).
     * 회원·게스트는 배타적이라 [userId]·[guestDeviceId] 중 하나만 채워지며, 둘 다 null이면 아무것도 하지 않는다.
     *
     * [gate]로 최초 1회만 실행하고, 회원 환불은 [reward]의 멱등 키([refundKey])로 한 번 더 방어한다 — 즉시 환불(catch)과
     * 안전망(onCompletion)이 겹쳐도 원장에 REFUND 행은 정확히 1건만 남는다. 환불·복원 실패가 SSE 종료를 막지 않도록
     * 예외는 삼키고 로그만 남긴다(회원은 선차감이 원장에 있어 사후 정산·재시도로 복구 가능).
     */
    private fun refundChatTurn(
        userId: Long?,
        guestDeviceId: String?,
        memberTrialCovered: Boolean,
        chatPk: Long,
        refundKey: String,
        gate: AtomicBoolean,
    ) {
        if (userId == null && guestDeviceId == null) return
        if (!gate.compareAndSet(false, true)) return
        try {
            if (memberTrialCovered && userId != null) {
                // 이 턴이 체험 잔여로 무료 처리됐으면 크레딧이 아니라 회원 체험 카운터를 되돌린다(스펙 §4-3-7 B13).
                guestTrialLimitService.restoreMember(userId, GuestTrialLimitService.Counter.CHAT_TURN)
            } else if (userId != null) {
                creditWalletService.reward(
                    userId = userId,
                    amount = chatTurnCost,
                    reason = CreditReason.REFUND,
                    idempotencyKey = refundKey,
                    refType = "CHAT",
                    refId = chatPk,
                )
            } else if (guestDeviceId != null) {
                guestTrialLimitService.restore(guestDeviceId, GuestTrialLimitService.Counter.CHAT_TURN)
            }
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
    private fun assembleAiRequest(chat: StoryChat, story: Story?, userInput: String): ChatTurnAiRequest =
        buildAiRequest(chat, story, assembleHistory(chat.id), userInput)

    /**
     * 스토리 설정·시작 설정과 주어진 [history]·[userInput]으로 AI 채팅 턴 요청을 조립한다.
     * 이어쓰기는 전체 내역을, 재생성(§4-3-9)은 마지막 턴을 제외한 내역을 [history]로 넘긴다.
     */
    private fun buildAiRequest(
        chat: StoryChat,
        story: Story?,
        history: List<ChatHistoryMessage>,
        userInput: String,
    ): ChatTurnAiRequest {
        val genre = story?.genre.orEmpty()
        val setting = storySettingRepository.findByStoryId(chat.storyId)
        val startSetting = chat.startSettingId?.let { storyStartSettingRepository.findById(it).orElse(null) }

        // 주요 사건·엔딩 런타임 재료(§4-3-10, D11). AI가 무상태이므로 백엔드가 매 턴 되돌려 싣는다.
        val mainEvents = storyMainEventRepository.findByStoryIdOrderBySortOrderAsc(chat.storyId)
        val targetMainEvent = chat.targetMainEventId?.let { targetId ->
            mainEvents.firstOrNull { it.id == targetId }
                ?.let { ChatTurnTargetMainEvent(name = it.name, progressTurns = chat.targetProgressTurns) }
        }

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
            history = history,
            userInput = userInput,
            summary = "",
            mainEvents = mainEvents.map { ChatTurnMainEvent(it.name, it.description, it.keySentence) },
            targetMainEvent = targetMainEvent,
            occurredMainEventNames = resolveOccurredMainEventNames(chat.id, mainEvents),
            endings = loadEligibleEndings(chat),
        )
    }

    /** 채팅이 거쳐온(완결) 사건 이름을 주요 사건 표시 순서로 반환한다(occurred_main_event_names 재료). */
    private fun resolveOccurredMainEventNames(chatId: Long, mainEvents: List<StoryMainEvent>): List<String> {
        if (mainEvents.isEmpty()) {
            return emptyList()
        }
        val occurredIds = storyChatMainEventRepository.findByChatId(chatId).map { it.mainEventId }.toSet()
        return mainEvents.filter { it.id in occurredIds }.map { it.name }
    }

    /**
     * 이번 턴 도달 후보 엔딩만 싣는다: 이미 도달한 채팅(reached_ending_id != null)이면 빈 목록(재판정 차단),
     * 그 외엔 이번 턴(current_turn + 1)이 최소 턴 수를 충족하는 활성 엔딩만. 최소 턴 수 판정은 백엔드 결정 몫(§4-3-10).
     */
    private fun loadEligibleEndings(chat: StoryChat): List<ChatTurnEnding> {
        if (chat.reachedEndingId != null) {
            return emptyList()
        }
        val startSettingId = chat.startSettingId ?: return emptyList()
        val turnBeingGenerated = chat.currentTurn + 1
        return storyEndingRepository.findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSettingId)
            .filter { it.minTurns <= turnBeingGenerated }
            .map { ChatTurnEnding(name = it.name, achievementCondition = it.achievementCondition, epilogue = it.epilogue) }
    }

    /** AI completed 결과의 판정 필드를 저장 트랜잭션용 [TurnJudgment]로 변환한다. */
    private fun ChatTurnAiResult.toTurnJudgment(): TurnJudgment = TurnJudgment(
        targetMainEvent = targetMainEvent?.let { TargetMainEventJudgment(it.name, it.progressTurns) },
        occurredMainEventName = occurredMainEventName,
        endingName = endingName,
    )

    /**
     * 채팅을 시작할 시작 설정을 해소한다(KNK-515 복수화). [startSettingPublicId] 미지정이면 스토리의 첫(기본) 시작 설정을,
     * 지정하면 그 공개 식별자로 조회하되 반드시 이 스토리 소속이어야 한다. 형식 오류·미존재·타 스토리 소속은 모두 404다
     * (존재 노출 최소화·조용한 폴백 금지). 시작 설정이 하나도 없는 스토리는(미지정 경로) null을 반환해 빈 프롤로그·추천 입력으로 시작한다.
     */
    private fun resolveStartSetting(storyId: Long, startSettingPublicId: String?): StoryStartSetting? {
        if (startSettingPublicId == null) {
            return storyStartSettingRepository.findFirstByStoryIdOrderByIdAsc(storyId)
        }
        val publicId = parsePublicIdOrNull(startSettingPublicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "시작 설정을 찾을 수 없습니다.")
        val startSetting = storyStartSettingRepository.findByPublicId(publicId)
        if (startSetting == null || startSetting.story.id != storyId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "시작 설정을 찾을 수 없습니다.")
        }
        return startSetting
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

    /** [resolveChat]과 같으나 행에 비관적 쓰기 락을 걸어 조회한다(삭제 소유권 검사의 마이그레이션 클레임 경쟁 차단 — KNK-69). */
    private fun resolveChatForUpdate(publicId: String): StoryChat {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
        return storyChatRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsed)
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
