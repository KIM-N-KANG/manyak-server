package com.knk.manyak.chat.service

import com.knk.manyak.global.util.parsePublicIdOrNull
import com.knk.manyak.story.service.UsernameTokenRenderer.render
import com.knk.manyak.chat.dto.ChangeChatSettingsResponse
import com.knk.manyak.chat.dto.ChatPersonaResponse
import com.knk.manyak.user.service.UserPersonaService
import com.knk.manyak.chat.client.ChatTurnGenerationDeadlineException
import com.knk.manyak.chat.client.ChatTurnAiClient
import com.knk.manyak.chat.client.ChatTurnAiException
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.dto.ChatChoicesResponse
import com.knk.manyak.chat.dto.ChatStreamCompletedEvent
import com.knk.manyak.chat.dto.ChatStreamErrorEvent
import com.knk.manyak.chat.dto.ChatStreamStartedEvent
import com.knk.manyak.chat.dto.ChatStreamCharacterImageEvent
import com.knk.manyak.chat.dto.ChatStreamTokenEvent
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.dto.CreateChatShareResponse
import com.knk.manyak.chat.dto.EditChatRequest
import com.knk.manyak.chat.dto.RegenerateChatRequest
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChatShare
import com.knk.manyak.chat.repository.StoryChatShareRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditTransaction
import com.knk.manyak.credit.service.CreditDeduction
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
import com.knk.manyak.story.service.StartSettingView
import com.knk.manyak.story.service.StoryPlayReader
import com.knk.manyak.story.service.StoryView
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import com.knk.manyak.chat.dto.GeneratingTurnResponse
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service
class ChatService(
    @Qualifier("chatSseExecutor")
    private val chatSseExecutor: Executor,
    private val chatGenerationStore: ChatGenerationStore,
    private val chatGenerationDeadlines: ChatGenerationDeadlines,
    private val chatGenerationEvents: ChatGenerationEvents,
    private val chatGenerationReceivers: ChatGenerationReceivers,
    // 채팅이 읽는 스토리 값은 전부 이 읽기 API를 거친다(KNK-2055). 호출자 트랜잭션에 합류한다.
    private val storyPlayReader: StoryPlayReader,
    private val personaService: UserPersonaService,
    private val storyChatRepository: StoryChatRepository,
    private val storyChatShareRepository: StoryChatShareRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val chatTurnAiClient: ChatTurnAiClient,
    private val chatTurnPersister: ChatTurnPersister,
    private val chatRealtimeImageService: ChatRealtimeImageService,
    private val structuredLogger: StructuredLogger,
    private val aiCallRecorder: AiCallRecorder,
    private val imageAiCalls: com.knk.manyak.global.observability.aicall.ChatImageAiCallRecorder,
    private val creditWalletService: CreditWalletService,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val suspensionGuard: SuspensionGuard,
    private val serverAnalytics: ServerAnalytics,
    private val chatModelService: ChatModelService,
    // AI 턴 요청 조립과 그 조립 구간 트랜잭션 템플릿(KNK-2055에서 분리).
    private val chatTurnAssembler: ChatTurnAssembler,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
    transactionManager: PlatformTransactionManager,
) {

    private enum class TurnWorkerState { PENDING, RUNNING, SAVING, CANCELLED, FINISHED }

    private val turnSaveTransactionTemplate = TransactionTemplate(transactionManager)

    fun getModels(userId: Long? = null) = chatModelService.list(userId)

    @Transactional
    fun changeSettings(chatId: String, model: String?, userId: Long?, longResponse: Boolean? = null): ChangeChatSettingsResponse {
        suspensionGuard.requireActive(userId)
        val chat = storyChatRepository.resolveChat(chatId)
        requireChatOwner(chat, userId)
        if (model == null && longResponse == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "채팅 설정을 하나 이상 보내야 합니다.")
        }
        val code = model?.let(chatModelService::requireSelectable)
        fun requireUpdated(count: Int) {
            if (count != 1) throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 변경할 권한이 없습니다.")
        }
        code?.let { requireUpdated(storyChatRepository.updateModel(chat.id, it, userId)) }
        longResponse?.let { requireUpdated(storyChatRepository.updateLongResponse(chat.id, it, userId)) }
        val settings = storyChatRepository.findSettingsById(chat.id)
        return ChangeChatSettingsResponse(chat.publicId.toString(), chatModelService.effective(settings.modelCode).code, settings.longResponse)
    }

    @Transactional
    fun createChat(request: CreateChatRequest, userId: Long? = null): CreateChatResponse {
        suspensionGuard.requireActive(userId)
        val persona = request.personaId?.let {
            if (userId == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "페르소나 선택은 회원만 가능합니다.")
            personaService.snapshot(userId, it)
        }
        // 스토리 공개 식별자(public_id)로 받아 삭제되지 않은 내부 스토리를 조회한다.
        // 이 한 번의 조회가 KNK-256(public_id 해석)과 KNK-257(삭제된 스토리로 채팅 생성 차단)을 함께 처리한다.
        // 형식 오류·미존재·삭제는 모두 404로 통일된다.
        val story = resolveStory(request.storyId, userId)
        val chatName = persona?.name ?: story.protagonistName
        // 공개(PUBLISHED∧PUBLIC) 스토리이거나 소유자만 채팅을 시작할 수 있다(KNK-401). 비공개·초안은 소유자 외엔 404.
        if (!story.readable) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        }
        // 소유자 없는 스토리는 발행 상태이며 공개일 때만 회원이 채팅을 시작할 수 있다(KNK-2038).
        // 그 밖의 소유자 없는 스토리는 익명 요청만 허용한다.
        if (story.userId == null && userId != null && !story.publiclyVisible) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "이 스토리로는 채팅을 시작할 수 없습니다.")
        }
        // 시작 설정 복수화(KNK-515): startSettingId를 지정하면 그 시작 설정으로, 미지정이면 스토리의 첫(기본) 시작 설정으로 시작한다.
        // 지정 값이 형식 오류거나 이 스토리에 속하지 않으면 404로 통일한다(존재 노출 최소화·조용한 폴백 금지).
        val startSetting = resolveStartSetting(story.id, request.startSettingId)

        val chat = storyChatRepository.save(
            StoryChat(
                userId = userId,
                modelCode = chatModelService.defaultModel().code,
                storyId = story.id,
                personaNameSnapshot = persona?.name,
                personaDescriptionSnapshot = persona?.description,
                startSettingId = startSetting?.id,
                // AI trace 여정(KNK-751): 이 스토리를 만든 간편 제작 세션의 creation_id를 여기서 **한 번만** 해석해 박는다.
                // 턴마다 역조회하지 않기 위해서다. 일반 제작(저작) 스토리는 세션이 없어 null이고 헤더가 생략된다.
                creationId = storyPlayReader.findCreationId(story.id),
                // 프롤로그는 시작 설정이 삭제되면 조회 키(start_setting_id)가 NULL이 돼 되찾을 수 없어 박아 둔다.
                // 제목·썸네일 스냅샷은 읽기 정본이 stories.last_public_snapshot으로 옮겨가 V71에서 지웠다.
                storyPrologueSnapshot = startSetting?.prologue?.let { render(it, chatName) },
            ),
        )
        structuredLogger.event(
            "chat_started",
            // 로그의 story_id는 story_created·분석 이벤트와 조인되도록 공개 식별자(public UUID)로 남긴다.
            "story_id" to story.publicId.toString(),
            "chat_id" to chat.publicId.toString(),
        )

        val suggestedInputs = storyPlayReader.loadSuggestedInputs(startSetting?.id)

        return CreateChatResponse(
            model = chat.modelCode,
            longResponse = chat.longResponse,
            id = chat.publicId.toString(),
            storyId = story.publicId.toString(),
            persona = persona?.let { ChatPersonaResponse(it.name) },
            prologue = render(startSetting?.prologue.orEmpty(), chatName),
            suggestedInputs = suggestedInputs.map { render(it, chatName) },
            createdAt = chat.createdAt,
        )
    }

    /**
     * 채팅 공유 링크를 발급한다(스펙 §4-3-11). 요청 본문은 없고, 발급 시점의 진행 턴 수(current_turn)를
     * 커트라인으로 고정한다 — 메시지를 복사하지 않으므로 열람이 이 커트라인 이하 턴만 조립한다.
     *
     * 같은 (채팅, 커트라인) 조합의 공유가 이미 있으면 새로 만들지 않고 그대로 반환한다(멱등 — 중복 클릭·재발급 안전).
     * 턴이 진행된 뒤 발급하면 새 커트라인의 공유가 새로 생기며 기존 공유도 계속 유효하다.
     *
     * 삭제 경로(KNK-69)와 동일하게 채팅 행에 비관적 쓰기 락을 걸고 소유권 검사와 발급을 한 트랜잭션으로 묶는다(Codex P2).
     * 락이 없으면 소유권 검사와 삽입 사이에 이관 클레임([StoryChatRepository.claimByPublicId])이 끼어들어,
     * `user_id IS NULL`로 검사를 통과한 익명 요청이 방금 회원 소유가 된 채팅에 공유를 만든다. 같은 락이
     * 동시 발급도 채팅 단위로 직렬화하므로, 뒤따르는 요청은 상대가 커밋한 공유를 조회로 발견해 멱등이 성립한다
     * (`uq_story_chat_shares_chat_cutoff`는 DB 레벨 최후 방어로 남는다).
     */
    @Transactional
    fun createChatShare(chatId: String, userId: Long?): CreateChatShareResponse {
        val chat = resolveChatForUpdate(chatId)
        // 소유권 게이트(§4-5): 채팅 상세 조회와 동일 규칙 — 소유 채팅은 소유자만, NULL 채팅은 게스트만. 위반 403.
        // 존재 여부를 노출하지 않도록 404(없음·삭제) 판정 뒤에 적용한다.
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 공유할 권한이 없습니다.")
        }

        val turnCutoff = chat.currentTurn
        val share = storyChatShareRepository.findByChatIdAndTurnCutoff(chat.id, turnCutoff)
            ?: storyChatShareRepository.save(StoryChatShare(chatId = chat.id, turnCutoff = turnCutoff))

        return CreateChatShareResponse(
            shareId = share.publicId.toString(),
            turnCount = share.turnCutoff,
            createdAt = share.createdAt,
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
     * 턴이 completed 없이 끝나면(error·생성 마감·미완료) 선차감분·예약분을 전액 환불·복원한다(정확히 1회, 멱등 키로 이중 방어).
     */
    fun editChatTurn(chatId: String, request: EditChatRequest, userId: Long? = null, deviceId: String? = null): SseEmitter =
        streamChatTurn(chatId, ContinueChatRequest(userInput = request.userInput, userSource = request.userSource,
            realtimeImage = request.realtimeImage), userId, deviceId, editTurnId = request.turnId)

    private fun resolveEditTurnNumber(chatId: Long, turnId: Long): Int {
        val messages = storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chatId)
        val assistants = messages.filter { it.role == MessageRole.ASSISTANT }
        val index = assistants.indexOfFirst { it.id == turnId }
        if (index < 0 || messages.none { it.role == MessageRole.USER && it.messageOrder < assistants[index].messageOrder }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "수정할 턴을 찾을 수 없습니다.")
        }
        return index + 1
    }

    fun streamChatTurn(
        chatId: String,
        request: ContinueChatRequest,
        userId: Long? = null,
        deviceId: String? = null,
        editTurnId: Long? = null,
    ): SseEmitter = streamEntry { recordResult ->
        // 선택 기록의 세대 가드 기준 시각(KNK-819). **AI 호출 전인 요청 진입 시점**에 잡아야 한다 — 사용자는
        // 요청을 보내기 전에 선택지를 봤으므로, 이 시각 이후에 만들어진 선택지 행은 재생성이 갈아끼운 세대다.
        val requestedAt = Instant.now()
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        // 채팅을 공개 식별자로 먼저 검증한다(없으면 동기 404). 이후 내부 PK로 저장·이력을 처리하고,
        // SSE 이벤트에는 외부에 노출하는 공개 식별자(chatId)만 싣는다.
        val chat = storyChatRepository.resolveChat(chatId)
        // 소유권 강제(스펙 §4-5): 회원 소유 채팅(userId != null)은 소유자만 이어쓸 수 있다.
        // 토큰 누락·만료(요청 userId == null)나 타 회원이 owned 채팅에 이어쓰면 403으로 막는다.
        // 이 검사가 없으면 토큰을 빼 게스트로 위장해 소유 채팅을 무료로 이어써 선차감을 우회할 수 있다(Codex P1).
        // 게스트 채팅(chat.userId == null)은 게스트(요청 userId == null)만 허용하며 차감이 없다(회원은 이관 후 접근, KNK-480).
        requireChatOwner(chat, userId)
        if (editTurnId != null) resolveEditTurnNumber(chat.id, editTurnId)
        val generation = acquireGeneration(chat, if (editTurnId == null) "CONTINUE" else "EDIT", request.userInput, requestedAt, editTurnId)
        var streamingOwnsGeneration = false
        try {
            val prepared = requireNotNull(chatTurnAssembler.assemblyTransactionTemplate.execute {
                val freshChat = storyChatRepository.resolveChat(chatId)
                val state = generationStartState(freshChat)
                if (request.hasLastTurnId && request.lastTurnId != state.lastTurnId) {
                    throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.CHAT_TURN_STALE, "마지막 턴이 변경되었습니다.")
                }
                val targetNumber = editTurnId?.let { resolveEditTurnNumber(chat.id, it) } ?: (freshChat.currentTurn + 1)
                Triple(freshChat, state, chatTurnAssembler.assembleAiRequest(freshChat, request.userInput, targetNumber, request.userSource))
            })
            val startState = prepared.second
            val aiCall = prepared.third
            val storyPublicId = storyPlayReader.findPublicId(chat.storyId)?.toString().orEmpty()

            streamingOwnsGeneration = true // 이후의 키 해제는 스트리밍 처리가 소유한다.
            return@streamEntry streamTurnInternal(
                chatId = chatId,
                chat = prepared.first,
                generation = generation,
                recordResult = recordResult,
                storyPublicId = storyPublicId,
                aiCall = aiCall,
                userId = userId,
                deviceId = deviceId,
                isRegenerated = false,
                realtimeImage = request.realtimeImage,
                persist = { result, chargeId, billingModelCode ->
                    chatTurnPersister.persistTurn(
                        chatId = chat.id,
                        editTurnId = editTurnId,
                        storyPublicId = aiCall.traceLink.storyId,
                        expectedState = startState,
                        creditTransactionId = chargeId,
                        billingModelCode = billingModelCode,
                        userInput = request.userInput,
                        aiOutput = result.aiOutput,
                        choices = result.choices,
                        judgment = result.toTurnJudgment(),
                        // 선택 기록(KNK-819)은 프론트가 보낸 값을 그대로 넘긴다 — 유효성 판정은 저장 트랜잭션 안에서 한다.
                        // 여기서 미리 거르면 락 밖 검사가 되어, AI 호출(생성 마감까지) 동안 마지막 턴이 바뀐 경우를 놓친다.
                        // requestedAt은 그 판정의 세대 기준이다(위 진입 시점에 확정).
                        selection = ChoiceSelection(request.sourceTurnId, request.choiceOrder, requestedAt),
                        // 조립이 AI에게 보낸 그 목록. 저장 판정이 같은 출처를 봐야 비공개 전환 후 교체된
                        // 스토리에서도 도달·사건이 누락되지 않는다(PR #224 Codex P2).
                        judgmentSource = aiCall.judgmentSource,
                    )
                },
                onPersisted = { persistedTurn, aiCallLogId, modelCode, turnCost ->
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
                        "model_code" to modelCode,
                        "turn_cost" to turnCost,
                        "long_response" to aiCall.request.longResponse,
                    )
                },
            )
        } catch (ex: Throwable) {
            if (!streamingOwnsGeneration) releaseGeneration(chat, generation)
            throw ex
        }
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
    ): SseEmitter = streamEntry { recordResult ->
        val requestedAt = Instant.now()
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        val chat = storyChatRepository.resolveChat(chatId)
        requireChatOwner(chat, userId)
        // 엔딩 도달 턴은 재생성 대상이 아니다(§4-3-10 도달 기록 확정 후). 도달 기록이 채팅을 ENDED로 굳히므로 여기서 막는다.
        if (chat.status == ChatStatus.ENDED) {
            throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.CHAT_ENDED, "엔딩에 도달한 채팅은 재생성할 수 없습니다.")
        }
        val initialTarget = resolveRegenerateTarget(chat.id, request.turnId)
        val generation = acquireGeneration(chat, "REGENERATE", initialTarget.userInput, requestedAt, request.turnId)
        var streamingOwnsGeneration = false
        try {
            val prepared = requireNotNull(chatTurnAssembler.assemblyTransactionTemplate.execute {
                val freshChat = storyChatRepository.resolveChat(chatId)
                if (freshChat.status == ChatStatus.ENDED) {
                    throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.CHAT_ENDED, "엔딩에 도달한 채팅은 재생성할 수 없습니다.")
                }
                // 마지막 턴 검증과 재생성용 history(마지막 턴 제외)·재전송 입력을 같은 스냅샷에서 확정한다.
                val target = resolveRegenerateTarget(chat.id, request.turnId)
                val state = generationStartState(freshChat)
                // 재생성은 마지막 턴을 교체하므로 대상 턴 번호는 current_turn 그대로다(+1 아님).
                Triple(freshChat, state, chatTurnAssembler.buildAiRequest(freshChat, target.userInput, freshChat.currentTurn, isRegenerated = true))
            })
            val startState = prepared.second
            val aiCall = prepared.third
            val storyPublicId = storyPlayReader.findPublicId(chat.storyId)?.toString().orEmpty()

            streamingOwnsGeneration = true // 이후의 키 해제는 스트리밍 처리가 소유한다.
            return@streamEntry streamTurnInternal(
                chatId = chatId,
                chat = prepared.first,
                generation = generation,
                recordResult = recordResult,
                storyPublicId = storyPublicId,
                aiCall = aiCall,
                userId = userId,
                deviceId = deviceId,
                isRegenerated = true,
                realtimeImage = request.realtimeImage,
                persist = { result, chargeId, billingModelCode ->
                    // 현재 채팅 상태로 요청하고 판정 메타는 재적용하지 않는다. 직전 턴 상태를 복원해 새 판정을
                    // 반영하려면 턴별 메타 델타 이력이 필요하지만 현 스키마에는 없다. 엔딩 도달 턴은 위에서
                    // 거절하므로 최초 도달 불변식은 유지된다(regenerateLastTurn은 judgment를 받지 않음).
                    chatTurnPersister.regenerateLastTurn(
                        chatId = chat.id,
                        expectedAssistantId = request.turnId,
                        expectedState = startState,
                        creditTransactionId = chargeId,
                        billingModelCode = billingModelCode,
                        aiOutput = result.aiOutput,
                        choices = result.choices,
                    )
                },
                onPersisted = { persistedTurn, aiCallLogId, modelCode, turnCost ->
                    structuredLogger.event(
                        "ai_response_regenerated",
                        "chat_id" to chatId,
                        "story_id" to storyPublicId,
                        "turn_number" to persistedTurn.turnNumber,
                        "ai_call_log_id" to aiCallLogId,
                        "model_code" to modelCode,
                        "turn_cost" to turnCost,
                        "long_response" to aiCall.request.longResponse,
                    )
                },
            )
        } catch (ex: Throwable) {
            if (!streamingOwnsGeneration) releaseGeneration(chat, generation)
            throw ex
        }
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
        val prepared = requireNotNull(chatTurnAssembler.assemblyTransactionTemplate.execute {
            val chat = storyChatRepository.resolveChat(chatId)
            requireChatOwner(chat, userId)
            val target = resolveRegenerateTarget(chat.id, turnId)
            val existing = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(turnId)
            if (existing.isNotEmpty()) return@execute ChoicesAssembly(chat, target, null, existing.map { it.choiceText })
            ChoicesAssembly(chat, target, chatTurnAssembler.buildAiRequest(chat, target.userInput, chat.currentTurn, isRegenerated = false), null)
        })
        prepared.existing?.let { return ChatChoicesResponse(it) }
        val chat = prepared.chat
        val target = prepared.target
        val aiCall = requireNotNull(prepared.aiCall)

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
                chatTurnAiClient.generateChoices(aiCall.request, target.aiOutput, aiCall.traceLink)
            }
        } catch (exception: Exception) {
            // 선택지 생성 실패는 502로 올려 프론트가 재시도한다(본문·판정은 이미 저장돼 있어 영향 없음, 스펙 §4-3-3).
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 선택지 생성 요청에 실패했습니다.", exception)
        }

        // 락 안에서 본문 재생성 경합을 검사하고 저장한다. 반환값은 실제 저장된 선택지다(경합 시 recorded와 다를 수 있음, Codex P2).
        val filled = chatTurnPersister.fillChoices(chat.id, turnId, target.aiOutput, recorded.result.choices)
        // ai_call_logs.turn_number를 채워 chat_response 행과 chat_id + turn_number로 조인되게 한다(§4-7).
        aiCallRecorder.attachTurnNumber(recorded.aiCallLogId, filled.turnNumber)
        return ChatChoicesResponse(filled.choices)
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
            throw CodedResponseStatusException(HttpStatus.CONFLICT, ApiErrorCodes.CHAT_TURN_STALE, "마지막 턴이 아니거나 이미 변경되었습니다.")
        }
        // 마지막 턴의 USER 입력(마지막 ASSISTANT 직전 USER). 그대로 재전송한다(AI 서버는 무상태).
        val pairedUser = all.lastOrNull { it.role == MessageRole.USER && it.messageOrder < lastAssistant.messageOrder }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "재생성할 턴의 사용자 입력을 찾을 수 없습니다.")
        return RegenerateTarget(
            assistantId = lastAssistant.id,
            userInput = pairedUser.content,
            aiOutput = lastAssistant.content,
        )
    }

    private data class ChoicesAssembly(val chat: StoryChat, val target: RegenerateTarget, val aiCall: AiTurnCall?, val existing: List<String>?)

    private data class RegenerateTarget(
        val assistantId: Long,
        val userInput: String,
        // 마지막 턴의 저장된 AI 본문. 선택지 생성 시 ai_output으로 되싣는다(재생성은 사용하지 않음).
        val aiOutput: String,
    )

    private fun generationStartState(chat: StoryChat) = GenerationStartState(
        storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chat.id).lastOrNull { it.role == MessageRole.ASSISTANT }?.id,
        chat.revision,
    )

    private fun streamEntry(block: (TurnResultRecorder) -> SseEmitter): SseEmitter {
        val resultRecorded = AtomicBoolean(false)
        val recordResult = TurnResultRecorder { outcome, model ->
            if (resultRecorded.compareAndSet(false, true)) recordChatTurnResult(outcome, model)
        }
        return try {
            block(recordResult)
        } catch (ex: Throwable) {
            if (isChatTurnClientRejection(ex)) recordResult.modelCode = "none"
            recordResult(if (isChatTurnClientRejection(ex)) OUTCOME_REJECTED else OUTCOME_FAILURE)
            throw ex
        }
    }

    private class TurnResultRecorder(private val record: (String, String) -> Unit) {
        @Volatile var modelCode: String = "none"
        operator fun invoke(outcome: String) = record(outcome, modelCode)
    }

    private fun acquireGeneration(chat: StoryChat, type: String, input: String, startedAt: Instant, target: Long?): GeneratingTurnResponse =
        chatGenerationStore.acquire(chat.publicId.toString(), type, input, startedAt, target)

    private fun releaseGeneration(chat: StoryChat, generation: GeneratingTurnResponse) {
        try {
            chatGenerationStore.release(chat.publicId.toString(), generation.generationId)
        } catch (ex: Exception) {
            structuredLogger.event("chat_generation_release_failed", "chat_id" to chat.publicId.toString(), "error" to ex.javaClass.simpleName)
        }
    }

    @Transactional(readOnly = true)
    fun resumeGeneration(chatId: String, generationId: String, userId: Long?): SseEmitter {
        val chat = storyChatRepository.resolveChat(chatId)
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 조회할 권한이 없습니다.")
        }
        val id = parsePublicIdOrNull(generationId)?.toString()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.")
        val publicChatId = chat.publicId.toString()
        val available = try {
            chatGenerationEvents.exists(publicChatId, id) || chatGenerationStore.get(publicChatId)?.generationId == id
        } catch (ex: Exception) {
            throw CodedResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "생성 상태를 확인할 수 없습니다.", ex)
        }
        if (!available) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.")
        }
        return try {
            chatGenerationReceivers.open(publicChatId, id)
        } catch (ex: java.util.concurrent.RejectedExecutionException) {
            throw CodedResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "생성 이벤트 수신 연결을 열 수 없습니다.", ex)
        }
    }

    /**
     * 채팅 턴/재생성 공통 SSE 스트리밍. 검증(404/403/409)과 AI 요청 조립은 호출부에서 동기로 끝낸 뒤 여기로 넘긴다.
     *
     * 회원([userId] != null)은 LITE 턴의 잔여 체험을 먼저 쓰고, 그 외에는 SseEmitter를 만들기 **전에**
     * 동기로 유효 모델의 1턴분을 선차감한다(잔액 부족 시 여기서
     * InsufficientCreditException이 던져져 컨트롤러가 402로 변환, 스트림 미개시). 실제 토큰 스트리밍과 저장은
     * [chatSseExecutor] 위에서 비동기로 처리한다. 저장([persist])이 completed 없이 끝나면(error·생성 마감·미완료·
     * 마지막 턴 변경) 선차감분을 전액 환불한다(정확히 1회, 멱등 키로 이중 방어).
     *
     * @param persist AI 결과를 원자적으로 저장(이어쓰기: 새 턴 추가 / 재생성: 마지막 턴 교체)하고 저장된 턴을 돌려준다.
     * @param onPersisted 저장 확정 직후의 관측 로그(이벤트 이름·필드가 이어쓰기/재생성마다 달라 호출부가 주입한다).
     */
    private fun streamTurnInternal(
        chatId: String,
        chat: StoryChat,
        generation: GeneratingTurnResponse,
        recordResult: TurnResultRecorder,
        storyPublicId: String,
        aiCall: AiTurnCall,
        userId: Long?,
        deviceId: String?,
        isRegenerated: Boolean,
        realtimeImage: Boolean,
        persist: (ChatTurnAiResult, Long?, String) -> ChatTurnPersister.PersistedTurn,
        onPersisted: (ChatTurnPersister.PersistedTurn, Long, String, Long) -> Unit,
    ): SseEmitter {
        val chatPk = chat.id
        var billingModel = aiCall.billingModel
        var memberTrialCovered = false
        var guestDeviceId: String? = null
        var imageReservation = ChatRealtimeImageService.Reservation(null)
        var imageCall: com.knk.manyak.global.observability.aicall.ChatImageAiCallRecorder.Call? = null
        var charges = emptyList<CreditTransaction>()
        val refundGate = AtomicBoolean(false) // 턴 체험/턴 비용만 소유한다. 이미지 환불과 공유하지 않는다.
        val trialRefundKey = "refund:chatturn:${UUID.randomUUID()}"
        fun turnCharge() = charges.firstOrNull { it.reason == CreditReason.CHAT_TURN }
        fun refundTurn() = refundChatTurn(
            userId, guestDeviceId, memberTrialCovered, chatPk,
            turnCharge()?.let { "refund:charge:${it.id}" } ?: trialRefundKey,
            refundGate, turnCharge()?.amount?.unaryMinus() ?: 0,
        )
        fun refundImage() {
            chatRealtimeImageService.restore(imageReservation)
            val charge = charges.firstOrNull { it.reason == CreditReason.CHAT_IMAGE } ?: return
            // DB 멱등 키가 행별 소유권이다. 저장 트랜잭션이 롤백되면 키도 롤백돼 finally에서 재시도할 수 있다.
            creditWalletService.reward(
                charge.userId, -charge.amount, CreditReason.REFUND, "refund:charge:${charge.id}",
                refType = "CHAT_IMAGE", refId = chatPk,
            )
        }
        fun refundImageQuietly() {
            imageAiCalls.finish(imageCall, errorCode = "AI_IMAGE_TURN_FAILED")
            try {
                refundImage()
            } catch (ex: Exception) {
                // 이미지 환불은 대사 배치 미대상(수용한 한계)이므로 실패를 별도로 기록한다.
                structuredLogger.event("chat_image_refund_failed", "chat_pk" to chatPk, "error" to ex.javaClass.simpleName)
            }
        }
        try {
            memberTrialCovered = userId != null && billingModel.turnCost > 0 && billingModel.code == "LITE" &&
                guestTrialLimitService.reserveMember(userId, GuestTrialLimitService.Counter.CHAT_TURN)
            guestDeviceId = guestTrialLimitService.reserveForGuestOrNull(userId, deviceId, GuestTrialLimitService.Counter.CHAT_TURN)
            if (userId == null || memberTrialCovered) billingModel = BillingModel("LITE", 0)
            recordResult.modelCode = billingModel.code
            imageReservation = chatRealtimeImageService.prepare(realtimeImage, userId, guestDeviceId)
            if (userId != null) {
                val items = buildList {
                    if (!memberTrialCovered && billingModel.turnCost > 0) add(CreditDeduction(billingModel.turnCost, CreditReason.CHAT_TURN, "CHAT_TURN", chatPk))
                    if (imageReservation.cost > 0) add(CreditDeduction(imageReservation.cost, CreditReason.CHAT_IMAGE, "CHAT_IMAGE", chatPk))
                }
                if (items.isNotEmpty()) charges = creditWalletService.deductBatch(userId, items).transactions
            }
            chatRealtimeImageService.issue(imageReservation, chat.publicId, requireNotNull(aiCall.traceLink.turnNumber))
            if (imageReservation.slot != null) imageCall = imageAiCalls.start(chat.storyId, chat.publicId)
        } catch (throwable: Throwable) {
            try {
                refundImageQuietly()
                refundTurn()
            } finally {
                releaseGeneration(chat, generation)
            }
            throw throwable
        }
        val persisted = AtomicBoolean(false)
        // PENDING 소유권은 워커 진입과 생성 마감 중 한쪽만 CAS로 선점한다.
        // RUNNING 마감과 SAVING 진입도 CAS로 선점한다. 실행된 워커의 환불은 finally만 결정한다.
        val workerState = AtomicReference(TurnWorkerState.PENDING)
        val events = chatGenerationEvents.writer(chat.publicId.toString(), generation.generationId, generation.startedAt)
        var emitter: SseEmitter? = null

        // 제출부터 워커 진입까지를 스팬으로 남긴다. chatSseExecutor가 포화되면 이 구간이 길어진다(KNK-1552).
        val queueWait = Observation.createNotStarted("chat.turn.queue", observationRegistry).start()
        val queueWaitFinished = AtomicBoolean(false)
        fun finishQueueWait(error: Throwable? = null) {
            if (!queueWaitFinished.compareAndSet(false, true)) return
            if (error != null) queueWait.lowCardinalityKeyValue("outcome", OUTCOME_FAILURE)
            if (error != null) queueWait.error(error)
            queueWait.stop()
        }

        val expirationGate = AtomicBoolean(false)
        fun expireGeneration() = synchronized(expirationGate) {
            // 클라이언트와 스케줄러가 겹치면 마감 이벤트 발행까지 끝난 뒤 워커가 정산한다.
            if (!expirationGate.compareAndSet(false, true)) return@synchronized
            val before = workerState.get()
            structuredLogger.event("chat_turn_timeout", "chat_id" to chatId, "worker_state" to before.name)
            val pending = workerState.compareAndSet(TurnWorkerState.PENDING, TurnWorkerState.CANCELLED)
            val cancelled = pending || workerState.compareAndSet(TurnWorkerState.RUNNING, TurnWorkerState.CANCELLED)
            if (cancelled) {
                serverAnalytics.chatAiMessageFailed(userId, chatId, null, "AI_STREAM_FAILED", isRegenerated, billingModel.code, aiCall.request.longResponse)
                events.write("error", ChatStreamErrorEvent("AI_STREAM_FAILED", "AI 응답 생성 중 오류가 발생했습니다."))
                if (pending) {
                    finishQueueWait(error = java.util.concurrent.TimeoutException("generation deadline exceeded"))
                    recordResult(OUTCOME_FAILURE)
                    refundImageQuietly()
                    refundTurn()
                    events.finish()
                    releaseGeneration(chat, generation)
                }
            }
        }
        val generationDeadline = generation.startedAt.plusMillis(GENERATION_TIMEOUT_MILLIS)
        fun deadlineReached() = !Instant.now().isBefore(generationDeadline)
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            deadline = chatGenerationDeadlines.schedule(
                { expireGeneration() },
                Duration.between(Instant.now(), generationDeadline).toMillis().coerceAtLeast(0),
            )
            // 연결 종료는 생성 상태와 정산을 바꾸지 않는다.
            emitter = chatGenerationReceivers.open(chat.publicId.toString(), generation.generationId)

            CompletableFuture.runAsync({
                if (deadlineReached()) expireGeneration()
                finishQueueWait()
                // CompletableFuture가 실행권을 얻은 뒤 취소가 끼어도 CANCELLED면 AI 호출·저장 없이 끝낸다.
                if (!workerState.compareAndSet(TurnWorkerState.PENDING, TurnWorkerState.RUNNING)) return@runAsync
                // AI 호출이 성공 반환하면 채운다. AI 호출 자체 실패는 record의 onFailure에서 캡처하므로 null로 남는다.
                var succeededAiCallLogId: Long? = null
                try {
                    if (workerState.get() == TurnWorkerState.CANCELLED) return@runAsync
                    events.write("started", ChatStreamStartedEvent(chatId, generation.generationId))
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
                            if (throwable !is ChatTurnGenerationDeadlineException && workerState.get() != TurnWorkerState.CANCELLED) {
                                captureChatFailure(aiCallLogId, throwable, storyPublicId, chatId)
                            }
                        },
                        // chat meta는 completed 결과(ChatTurnAiResult)에 실려 오므로, 같은 적재 저장에 반영한다.
                        meta = { it.meta },
                    ) {
                        imageAiCalls.bodyStarted(imageCall)
                        chatTurnAiClient.streamTurn(
                            aiCall.request.copy(imageSlots = listOfNotNull(imageReservation.slot), modelTier = billingModel.code),
                            aiCall.traceLink,
                            deadline = generationDeadline,
                            // AI가 스트리밍 중 보내는 인물 이미지 이벤트를 그대로 중계한다(KNK-943).
                            // 스트림 이벤트는 중계하고, 실시간 이미지의 발급 URL·객체 검증은 completed 저장 직전에 수행한다.
                            onCharacterImage = { characterImage ->
                                imageAiCalls.imageReceived(imageCall, characterImage.imageUrl, imageReservation.slot?.publicUrl)
                                if (workerState.get() == TurnWorkerState.CANCELLED) {
                                    throw CancellationException("chat turn stream cancelled")
                                }
                                events.write(
                                    "character_image",
                                    ChatStreamCharacterImageEvent(characterImage.name, characterImage.imageUrl),
                                )
                            },
                        ) { token ->
                            if (workerState.get() == TurnWorkerState.CANCELLED) {
                                throw CancellationException("chat turn stream cancelled")
                            }
                            events.write("token", ChatStreamTokenEvent(token))
                        }
                    }
                    succeededAiCallLogId = recorded.aiCallLogId
                    // 선택지는 턴 스트림에서 채우지 않는다(스펙 §4-3-3, B23): completed.choices는 항상 빈 배열이고 저장도 빈 상태로 시작한다.
                    // 프론트가 전용 트리거 엔드포인트(/turns/{turnId}/choices)로 선택지를 생성·저장한다(KNK-625 분리). 이로써 completed가
                    // 선택지 생성(90초)을 기다리지 않아 지연 이득을 회복한다. AI 계약상 turn 결과의 choices는 빈 배열이나, 계약을 확정적으로
                    // 유지하려 여기서 명시적으로 비운다(stub 등 잔여 값 방지).
                    // HEAD 검증 결과와 본문 저장은 같은 트랜잭션에서 확정한다.
                    // 검증 실패 예약은 즉시 복원한다. 이후 롤백 시 finally와 겹쳐도 Reservation CAS가 중복을 막는다.
                    // 이 CAS가 취소와 저장의 경계다. 취소가 이겼으면 늦은 completed도 저장하지 않는다.
                    if (deadlineReached()) expireGeneration()
                    if (!workerState.compareAndSet(TurnWorkerState.RUNNING, TurnWorkerState.SAVING)) return@runAsync
                    val (validated, persistedTurn) = requireNotNull(turnSaveTransactionTemplate.execute {
                        val checked = chatRealtimeImageService.validate(imageReservation, recorded.result.copy(choices = emptyList()))
                        val saved = persist(checked.result, turnCharge()?.id, billingModel.code)
                        if (!checked.success) refundImage()
                        checked to saved
                    })
                    val result = validated.result
                    // 저장이 확정된 순간 차감을 굳힌다(completed 전송 전). 이후 completed 전송이 실패하거나 클라이언트가
                    // 끊겨도 환불하지 않는다 — 저장된 턴은 이력에 남아 회원이 재조회로 볼 수 있으므로 과금이 정당하다(Codex P1).
                    persisted.set(true)
                    imageAiCalls.finish(imageCall, persistedTurn.turnNumber, validated.errorCode)
                    Sentry.addBreadcrumb("chat turn persisted: turn=${persistedTurn.turnNumber}", "db")
                    // 실제 turn 번호는 persist가 확정하므로, 적재된 호출에 그 값을 채워 정합성을 맞춘다.
                    aiCallRecorder.attachTurnNumber(recorded.aiCallLogId, persistedTurn.turnNumber)
                    onPersisted(persistedTurn, recorded.aiCallLogId, billingModel.code, turnCharge()?.amount?.unaryMinus() ?: 0)
                    // AI 응답 생성 성공 분석 이벤트(스펙 §6-4-2-6). 엔딩 도달 턴이면 도달 엔딩 id를 함께 싣는다(B5).
                    serverAnalytics.chatAiMessageSucceeded(
                        userId = userId,
                        chatId = chatId,
                        turnNumber = persistedTurn.turnNumber,
                        isRegenerated = isRegenerated,
                        endingId = persistedTurn.reachedEnding?.id?.toString(),
                        modelCode = billingModel.code,
                        longResponse = aiCall.request.longResponse,
                        turnCost = turnCharge()?.amount?.unaryMinus() ?: 0,
                    )
                    events.write(
                        "completed",
                        ChatStreamCompletedEvent(
                            chatId = chatId,
                            turnId = persistedTurn.turnId,
                            aiOutput = result.aiOutput,
                            choices = result.choices,
                            reachedEnding = persistedTurn.reachedEnding?.name?.let { render(it, aiCall.protagonistName) },
                        ),
                    )
                } catch (exception: ChatTurnGenerationDeadlineException) {
                    // 스케줄러가 늦어도 같은 CAS·로그·오류 이벤트 경로를 거쳐 finally에서 한 번 정산한다.
                    expireGeneration()
                } catch (exception: ChatTurnAiException) {
                    // 생성 마감과 실패 종료 중 하나만 종료권을 선점한다.
                    if (workerState.compareAndExchange(TurnWorkerState.RUNNING, TurnWorkerState.FINISHED) == TurnWorkerState.CANCELLED) return@runAsync
                    // AI가 내려준 구조화 오류는 code·message를 그대로 relay한다. 환불 판정은 아래 finally에 일원화한다.
                    events.write("error", ChatStreamErrorEvent(exception.code, exception.message))
                    // AI 응답 생성 실패 분석 이벤트(스펙 §6-4-2-6): 구조화 오류 code를 error_type으로 매핑한다. 저장 전이라 turn_number 미확정.
                    serverAnalytics.chatAiMessageFailed(
                        userId = userId,
                        chatId = chatId,
                        turnNumber = null,
                        errorCode = exception.code,
                        isRegenerated = isRegenerated,
                        modelCode = billingModel.code,
                        longResponse = aiCall.request.longResponse,
                    )
                } catch (exception: Exception) {
                    // 마감 오류 이벤트·실패 분석은 타이머가 이미 발행했다. 정산은 finally가 소유한다.
                    if (workerState.compareAndExchange(TurnWorkerState.RUNNING, TurnWorkerState.FINISHED) == TurnWorkerState.CANCELLED) return@runAsync
                    // AI 호출 자체 실패는 record의 onFailure(captureChatFailure)에서 이미 캡처했다(succeededAiCallLogId == null).
                    // record가 성공 반환한 뒤의 실패(예: persist DB·트랜잭션 오류·마지막 턴 변경, completed 전송 실패)는 GlobalExceptionHandler를
                    // 거치지 않고 여기서 삼켜지므로, 그 호출 id를 참조해 직접 캡처한다.
                    succeededAiCallLogId?.let {
                        captureChatFailure(it, exception, storyPublicId, chatId, attachToAiCallLog = false)
                    }
                    val errorCode = (exception as? CodedResponseStatusException)?.errorCode ?: "AI_STREAM_FAILED"
                    events.write("error", ChatStreamErrorEvent(errorCode, exception.reasonOrMessage()))
                    // 저장 성공 후 실패(completed 전송 실패 등)는 이미 성공 이벤트를 발행했으므로 중복 실패를 내지 않는다.
                    // 저장 전 실패(AI 호출 실패·persist 실패)만 실패 이벤트로 발행한다(스펙 §6-4-2-6).
                    if (!persisted.get()) {
                        serverAnalytics.chatAiMessageFailed(
                            userId = userId,
                            chatId = chatId,
                            turnNumber = null,
                            errorCode = errorCode,
                            isRegenerated = isRegenerated,
                            modelCode = billingModel.code,
                            longResponse = aiCall.request.longResponse,
                        )
                    }
                } finally {
                    // in-flight 환불 단일 판정: 워커가 저장 없이 빠져나가면(AI 오류·AI 호출 타임아웃·저장 전/저장 실패,
                    // 나아가 Error 등 어떤 종료든) 선차감분을 환불한다. 저장에 성공했으면(persisted) 과금을 유지한다.
                    // 이 판정을 워커에만 두어 타임아웃-저장 경합을 없앤다(onCompletion은 환불하지 않음, Codex P1). gate로 1회.
                    val persistedOk = persisted.get()
                    // 결과 판정은 이 finally·큐 대기 중 마감·스케줄 거부 catch 셋뿐이고 서로 배타적이다(KNK-811).
                    deadline?.cancel(false)
                    events.finish()
                    if (persistedOk) {
                        // 성공 시 turnSaveTransactionTemplate.execute의 턴 커밋 뒤에만 키를 해제한다.
                        // 상세는 Redis를 첫 SQL보다 먼저 읽으므로 이 순서가 키 부재와 저장된 턴의 정합성을 보장한다.
                        releaseGeneration(chat, generation)
                        workerState.set(TurnWorkerState.FINISHED)
                        recordResult(OUTCOME_SUCCESS)
                    } else {
                        try {
                            workerState.set(TurnWorkerState.FINISHED)
                            recordResult(OUTCOME_FAILURE)
                            refundImageQuietly()
                            refundTurn()
                        } finally {
                            // 즉시 재요청해도 복원 전 잔액·체험 한도를 보지 않도록 정산 시도 뒤 해제한다.
                            releaseGeneration(chat, generation)
                        }
                    }
                }
            }, chatSseExecutor)
        } catch (rejected: Throwable) {
            deadline?.cancel(false)
            finishQueueWait(error = rejected)
            // 스케줄 거부(chatSseExecutor 포화 시 RejectedExecutionException 등)는 위 async 블록이 실행되지 않아
            // 그 catch·onCompletion 환불이 돌지 않는다. 이미 선차감했으므로 여기서 환불한 뒤(gate로 1회) 예외를
            // 그대로 올려 호출자에게 실패로 드러낸다. 스트림은 열리지 않았으니 emitter를 오류로 닫아 반쯤 열린 상태를 막는다(Codex P1).
            // 워커가 생성되지 않은 경우 여기서 정산한다. 결과 집계는 streamEntry의 요청 단위 게이트를 공유한다.
            if (workerState.compareAndSet(TurnWorkerState.PENDING, TurnWorkerState.FINISHED)) {
                try {
                    refundImageQuietly()
                    refundTurn()
                } finally {
                    releaseGeneration(chat, generation)
                }
            }
            runCatching { emitter?.completeWithError(rejected) }
            structuredLogger.event(
                "chat_turn_schedule_rejected",
                "chat_id" to chatId,
                "story_id" to storyPublicId,
                "error" to (rejected.message ?: rejected::class.simpleName ?: "unknown"),
            )
            throw rejected
        }

        return requireNotNull(emitter)
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
        // 차감 때 쓴 금액. 환불은 반드시 같은 값이어야 한다(KNK-1056).
        amount: Long,
    ) {
        if (userId == null && guestDeviceId == null) return
        if (userId != null && !memberTrialCovered && amount == 0L) return
        if (!gate.compareAndSet(false, true)) return
        try {
            val restored = if (memberTrialCovered && userId != null) {
                // 이 턴이 체험 잔여로 무료 처리됐으면 크레딧이 아니라 회원 체험 카운터를 되돌린다(스펙 §4-3-7 B13).
                // restoreMember는 Redis 장애를 삼키고 정상 반환하므로, 성공 여부는 반환값으로만 알 수 있다(Codex P2).
                guestTrialLimitService.restoreMember(userId, GuestTrialLimitService.Counter.CHAT_TURN)
            } else if (userId != null && amount > 0) {
                creditWalletService.reward(
                    userId = userId,
                    amount = amount,
                    reason = CreditReason.REFUND,
                    idempotencyKey = refundKey,
                    refType = "CHAT_TURN",
                    refId = chatPk,
                )
                true
            } else if (guestDeviceId != null) {
                guestTrialLimitService.restore(guestDeviceId, GuestTrialLimitService.Counter.CHAT_TURN)
                true
            } else {
                true
            }
            // 환불 실패는 사용자가 실패한 턴에 과금된 채 남는다는 뜻이라 로그만으로는 부족하다(KNK-811).
            recordChatTurnRefund(if (restored) OUTCOME_SUCCESS else OUTCOME_FAILURE)
            if (!restored) {
                structuredLogger.event(
                    "chat_turn_refund_failed",
                    "chat_pk" to chatPk,
                    "idempotency_key" to refundKey,
                    "error" to "member_trial_restore_failed",
                )
            }
        } catch (exception: Exception) {
            recordChatTurnRefund(OUTCOME_FAILURE)
            structuredLogger.event(
                "chat_turn_refund_failed",
                "chat_pk" to chatPk,
                "idempotency_key" to refundKey,
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
    }

    /**
     * 채팅 턴 결과 분포를 `manyak.chat.turn.result`로 집계한다(KNK-811).
     *
     * outcome은 다음 값으로 분류한다:
     *   - `success`  : 턴이 저장까지 확정된 호출(`persisted`)
     *   - `failure`  : 저장에 도달하지 못하고 끝난 호출 — AI 실패·타임아웃·저장 오류·스케줄 거부
     *   - `cancelled`: 과거 조회용 값이며 더 이상 기록하지 않는다
     *   - `rejected` : 스트림 개시 **이전** 4xx — 크레딧 부족(402)·게스트 한도 소진(402)·device 헤더 누락(400)
     *
     * **Timer가 아니라 Counter인 이유**: 채팅 턴은 스트리밍이라 `manyak.ai.call.duration{feature="chat_response"}`이
     * 이미 스트림 전체를 재고 있어 유스케이스 타이머가 사실상 겹친다. 반면 `rejected`는 AI를 부르기 전에 끊겨
     * 그쪽에 남지 않는다.
     *
     * 요청 단위 결과 게이트를 공유한다. 동기 거부·큐 마감·워커 finally가 겹쳐도 한 번만 집계한다.
     * 실행 중 마감은 CANCELLED로 표시하고 워커 종료 시 failure, 저장 진입이 먼저면 저장 결과로 판정한다.
     */
    private fun recordChatTurnResult(outcome: String, modelCode: String) {
        // 관측 실패가 SSE 종료나 환불을 막지 않도록 격리한다.
        runCatching {
            Counter.builder("manyak.chat.turn.result")
                .description("채팅 턴 결과 분포")
                .tag("outcome", outcome)
                .tag("model_code", modelCode)
                .register(meterRegistry)
                .increment()
        }
    }

    /**
     * 채팅 턴 환불 결과를 `manyak.chat.turn.refund`로 집계한다(KNK-811).
     *
     * `failure`는 **선차감분을 되돌리지 못했다**는 뜻이다. 사용자가 실패한 턴에 과금된 상태로 남으므로
     * 관측 지표가 아니라 정산 정확성 신호로 읽는다(회원은 원장에 선차감이 남아 사후 정산으로 복구 가능).
     * 실제로 되돌릴 대상이 있고 게이트를 통과한 호출만 센다 — 무차감 게스트나 중복 호출은 여기 오지 않는다.
     *
     * 시계열은 [ChatTurnRefundMeters]가 기동 시 0으로 만들어 두므로 여기서 처음 등록되지 않는다.
     * 그 이유(첫 실패를 알림이 놓치는 문제)는 해당 클래스 KDoc에 있다.
     */
    private fun recordChatTurnRefund(outcome: String) {
        runCatching {
            Counter.builder(METRIC_CHAT_TURN_REFUND)
                .description("채팅 턴 선차감 환불 결과")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment()
        }
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
    private fun resolveStartSetting(storyId: Long, startSettingPublicId: String?): StartSettingView? {
        if (startSettingPublicId == null) {
            return storyPlayReader.findFirstStartSetting(storyId)
        }
        val publicId = parsePublicIdOrNull(startSettingPublicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "시작 설정을 찾을 수 없습니다.")
        return storyPlayReader.findStartSettingOfStory(storyId, publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "시작 설정을 찾을 수 없습니다.")
    }

    /** [resolveChat]과 같으나 행에 비관적 쓰기 락을 걸어 조회한다(삭제 소유권 검사의 마이그레이션 클레임 경쟁 차단 — KNK-69). */
    private fun resolveChatForUpdate(publicId: String): StoryChat {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
        return storyChatRepository.findByPublicIdAndDeletedAtIsNullForUpdate(parsed)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
    }

    /**
     * 스토리 공개 식별자(UUID 문자열)로 삭제되지 않은 스토리를 조회한다. 형식 오류·미존재·삭제는 404로 통일한다.
     * createChat이 KNK-256(public_id 해석)과 KNK-257(삭제 스토리 채팅 차단)을 한 조회로 처리하게 한다.
     */
    private fun resolveStory(publicId: String, requesterId: Long?): StoryView {
        val parsed = parsePublicIdOrNull(publicId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
        return storyPlayReader.findViewByPublicId(parsed, requesterId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "스토리를 찾을 수 없습니다.")
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
        if (throwable is ChatTurnAiException || isChatTurnClientRejection(throwable)) {
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

    private fun Exception.reasonOrMessage(): String =
        (this as? ResponseStatusException)?.reason ?: "AI 응답 생성 중 오류가 발생했습니다."

    // [ChatTurnRefundMeters]가 메트릭 이름·outcome 값을 공유해야 해서 private이 아니다.
    // 사전 등록과 increment가 같은 상수를 봐야 미터 신원이 갈리지 않는다.
    internal companion object {
        /**
         * 조립 구간 트랜잭션 설정. 게이트 판정에 쓰는 스토리와 그 자식 데이터(시작 설정·설정·사건·엔딩)를
         * **한 스냅샷에서** 읽어야 하기 때문에 REPEATABLE_READ다 — READ_COMMITTED에서는 쿼리 사이에 제작자의
         * "비공개 전환 + 수정" 커밋이 끼면 게이트는 옛 공개 상태로 열린 채 새 프롤로그가 AI 요청에 실린다.
         * 읽기 전용이라 PostgreSQL에서 락을 잡지 않는다. 회귀 가드(`ChatAssemblyTransactionGuardTests`)가 이 설정을 고정한다.
         */
        internal fun assemblyTransactionTemplate(
            transactionManager: PlatformTransactionManager,
        ): TransactionTemplate = TransactionTemplate(transactionManager).apply {
            isReadOnly = true
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

        // 생성 마감은 연결과 독립적으로 접수부터 계산한다.
        const val GENERATION_TIMEOUT_MILLIS = 120_000L

        // [ChatTurnRefundMeters]가 기동 시 이 이름으로 시계열을 0에 만들어 둔다. 사전 등록과 여기의
        // increment가 같은 미터를 가리켜야 하므로(이름·태그가 미터 신원이다) 리터럴을 쓰지 않는다.
        const val METRIC_CHAT_TURN_REFUND = "manyak.chat.turn.refund"

        // manyak.chat.turn.result·manyak.chat.turn.refund가 공유하는 outcome 태그 값(유한 enum —
        // 의미는 recordChatTurnResult·recordChatTurnRefund KDoc). 스토리 완성·스토리라인과 같은 어휘를 쓴다.
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_REJECTED = "rejected"

        // 연결 종료 취소가 있던 과거 조회용. 새 요청에는 기록하지 않는다.
        const val OUTCOME_CANCELLED = "cancelled"
    }
}

/**
 * 채팅 턴의 스트림 개시 전 실패가 **클라이언트 책임 거부(4xx)** 인지 판정한다(KNK-811, Codex P2).
 *
 * 이 구분이 필요한 이유: `rejected`는 실패 알림 축에서 제외된다. Redis·DB 장애로 예약·차감이 깨진 경우까지
 * 여기 넣으면 5xx로 나가는 운영 장애가 실패 신호에서 조용히 사라진다.
 *
 * 크레딧 부족은 [InsufficientCreditException]이라 `ResponseStatusException`이 **아니다** — 컨트롤러가 402로
 * 변환하므로 상태 코드만 보면 놓친다. 게스트 한도 소진·device 헤더 누락은 `CodedResponseStatusException`
 * (`ResponseStatusException` 하위)이라 4xx 검사에 걸린다.
 *
 * 클래스 밖 최상위로 둔 것은 상태가 필요 없는 순수 판정이고, 생성자 의존이 많은 [ChatService] 없이
 * 단위 테스트로 분류표를 고정하기 위해서다.
 */
internal fun isChatTurnClientRejection(throwable: Throwable): Boolean = when {
    throwable is InsufficientCreditException -> true
    throwable is ResponseStatusException && throwable.statusCode.is4xxClientError -> true
    else -> false
}

/**
 * 시작 설정에 연결된 추천 입력을 input_order 오름차순으로 조회한다.
 * 시작 설정이 없으면(startSettingId == null) 조회 없이 빈 목록을 반환한다.
 */
internal fun StoryPlayReader.loadSuggestedInputs(startSettingId: Long?): List<String> =
    startSettingId
        ?.let { findSuggestedInputs(it) }
        ?: emptyList()

/**
 * 공개 식별자(UUID 문자열)로 채팅을 조회한다. 형식이 잘못됐거나 존재하지 않으면 404로 통일한다.
 * 순차 정수든 임의 문자열이든 동일하게 404를 반환해 존재 여부를 노출하지 않는다.
 */
internal fun StoryChatRepository.resolveChat(publicId: String): StoryChat {
    val parsed = parsePublicIdOrNull(publicId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
    return findByPublicIdAndDeletedAtIsNull(parsed)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")
}
