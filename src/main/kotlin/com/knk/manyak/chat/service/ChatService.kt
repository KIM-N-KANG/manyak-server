package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatHistoryMessage
import com.knk.manyak.chat.client.ChatMessageRole
import com.knk.manyak.chat.client.ChatCharacterImage
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
import com.knk.manyak.chat.dto.ChatShareResponse
import com.knk.manyak.chat.dto.ChatShareTurnResponse
import com.knk.manyak.chat.dto.ChatStreamCompletedEvent
import com.knk.manyak.chat.dto.ChatStreamErrorEvent
import com.knk.manyak.chat.dto.ChatStreamStartedEvent
import com.knk.manyak.chat.dto.ChatStreamCharacterImageEvent
import com.knk.manyak.chat.dto.ChatStreamTokenEvent
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ChatTurnResponse
import com.knk.manyak.chat.dto.ContinueChatRequest
import com.knk.manyak.chat.dto.CreateChatRequest
import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.dto.CreateChatShareResponse
import com.knk.manyak.chat.dto.RegenerateChatRequest
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChatShare
import com.knk.manyak.chat.repository.StoryChatMainEventRepository
import com.knk.manyak.chat.repository.StoryChatShareRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.AiTraceLink
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
import com.knk.manyak.story.entity.EndingSnapshot
import com.knk.manyak.story.entity.MainEventSnapshot
import com.knk.manyak.story.entity.StartSettingSnapshot
import com.knk.manyak.story.entity.StoryPublicSnapshot
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.service.StoryPublicSnapshotService
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
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
    // 조립 재료를 현재 값·스냅샷 어느 쪽에서 오든 같은 모양으로 뜨는 몫(KNK-1065).
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
    private val imageUrlResolver: ImageUrlResolver,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyEndingRepository: StoryEndingRepository,
    // 목표 사건의 라이브 이름 해소용(스냅샷 분기에서 id가 빗나갈 때만 탄다).
    private val storyMainEventRepository: StoryMainEventRepository,
    // 채팅 요청에 실을 인물-이미지 매핑 조회용(KNK-943).
    private val storyCharacterRepository: StoryCharacterRepository,
    private val storyChatMainEventRepository: StoryChatMainEventRepository,
    private val storyChatRepository: StoryChatRepository,
    // 채팅 생성 시 스토리 → 간편 제작 세션 역조회로 creation_id를 1회 해석하는 데만 쓴다(KNK-751).
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyChatShareRepository: StoryChatShareRepository,
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
    // 채팅 턴 1회 소모량(재생성도 동일 값·사유를 공유). 운영 중 조정 가능한 정책값이라 턴마다 해석한다(KNK-1056).
    private val creditPolicyService: CreditPolicyService,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
) {

    /**
     * AI 턴 요청 **조립 구간 전용** 트랜잭션(KNK-1064 Codex P2).
     *
     * 턴 경로(이어쓰기·재생성·선택지)에는 트랜잭션이 없다 — AI 호출·스트리밍이 수십 초 커넥션을 붙드는 것을
     * 피하려는 의도된 설계다. 그래서 메서드에 `@Transactional`을 붙이는 대신(게다가 [buildAiRequest]는 private이라
     * self-invocation으로 프록시를 타지 못해 애초에 먹지도 않는다) 조립 구간만 이 템플릿으로 짧게 묶는다.
     * **조립이 끝나면 즉시 커밋되고 그 뒤에 AI를 부른다** — 트랜잭션이 AI 호출·스트리밍으로 넘어가지 않는다.
     */
    private val assemblyTransactionTemplate = assemblyTransactionTemplate(transactionManager)

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
                // AI trace 여정(KNK-751): 이 스토리를 만든 간편 제작 세션의 creation_id를 여기서 **한 번만** 해석해 박는다.
                // 턴마다 역조회하지 않기 위해서다. 일반 제작(저작) 스토리는 세션이 없어 null이고 헤더가 생략된다.
                creationId = storyCreationSessionRepository
                    .findFirstByStoryIdOrderByIdAsc(story.id)
                    ?.storylineRequestId,
                // 아래 셋은 **아무도 읽지 않는다** — 읽기 정본은 stories.last_public_snapshot이다(KNK-1065).
                // 그래도 계속 채운다: 롤링 배포 창의 구버전 태스크와 배포 되돌림이 이 값을 읽는다.
                // 다음 릴리스에서 컬럼과 함께 지운다([StoryChat] KDoc).
                storyTitleSnapshot = story.title,
                storyThumbnailKeySnapshot = story.thumbnailImageKey,
                storyPrologueSnapshot = startSetting?.prologue,
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

    // 게이트 판정(스토리)과 표시 값(시작 설정·엔딩)을 여러 쿼리로 나눠 읽으므로 한 스냅샷에 묶는다(KNK-1059).
    // 스토리 스냅샷은 게이트와 같은 행에 있어 이 경합에서 벗어났지만, **읽기가 허용된 분기는 여전히 자식 행을
    // 따로 읽으므로** 격리는 그대로 필요하다.
    // READ_COMMITTED에서는 쿼리 사이에 소유자의 "비공개 전환 + 수정" 커밋이 끼어들면 게이트는 옛 공개 상태로
    // 열린 채 자식 데이터만 새 값이 나와, 막으려던 유출이 그 틈으로 다시 샌다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        return toSummaryResponses(chats, userId)
    }

    /**
     * 회원 서재(KNK-447): 요청자가 소유한 채팅 카드를 최근 활동순(updatedAt)으로 반환한다. 소프트 삭제는 제외한다.
     * 카드 스키마는 [getChatsByIds](/chats/batch)와 동일하다([toSummaryResponses]).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getMyChats(userId: Long, limit: Int): List<ChatSummaryResponse> =
        toSummaryResponses(
            storyChatRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(userId, PageRequest.of(0, limit)),
            userId,
        )

    /**
     * 채팅 목록을 카드 응답으로 매핑한다. 스토리 제목·마지막 프리뷰를 각각 한 번의 배치 조회로 채운다(N+1 방지).
     * 입력 순서를 그대로 보존하므로, 정렬은 호출부(요청 순서·최근 활동순)에서 결정한다.
     */
    private fun toSummaryResponses(chats: List<StoryChat>, userId: Long?): List<ChatSummaryResponse> {
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
        // 스냅샷은 **읽을 수 없는 스토리에만** 필요하다. 별도 테이블이라 그 스토리들만 한 번에 조회한다
        // (스토리 엔티티에 붙어 있었다면 목록 전체가 이 JSON을 함께 읽었다 — PR #224 Codex P2).
        val snapshotByStoryId = storyPublicSnapshotService.findAllByStoryIds(
            storiesByStoryId.values.filterNot { it.isCurrentMetadataVisibleTo(userId) }.map { it.id },
        )
        return chats.map { chat ->
            val story = storiesByStoryId[chat.storyId]
            // 제목·썸네일은 요청자가 지금 그 스토리를 읽을 수 있을 때만 현재 값을 쓴다(KNK-1059).
            // 비공개로 되돌렸거나 삭제됐으면 채팅에 박아둔 스냅샷에서 멈춘다. storyId(public_id)는
            // 요청자가 원래 알던 값이라 그대로 둔다 — 새로 새는 정보가 없다.
            val showsCurrent = story?.isCurrentMetadataVisibleTo(userId) == true
            // 읽을 수 없으면 그 스토리가 **마지막으로 공개였던 시점**의 스냅샷에서 멈춘다(KNK-1065).
            val snapshot = if (showsCurrent) null else story?.let { snapshotByStoryId[it.id] }
            ChatSummaryResponse(
                id = chat.publicId.toString(),
                storyId = story?.publicId?.toString().orEmpty(),
                storyTitle = (if (showsCurrent) story.title else snapshot?.title).orEmpty(),
                // 채팅 카드(46×62)도 목록과 같은 축소 변형을 공유한다(스펙 §4-3-9 반응형 변형).
                // 생성 표지(KNK-1069)와 프리셋 키의 2단 폴백은 ImageUrlResolver가 소유한다.
                // 읽을 수 없으면 **둘 다** 마지막 공개 버전 스냅샷에서 읽는다 — 스냅샷이 URL까지 담으므로
                // 비공개로 되돌린 스토리의 카드가 프리셋 표지로 내려앉지 않는다(KNK-1069가 수용했던 화면 열화).
                thumbnailUrlSm = if (showsCurrent) {
                    imageUrlResolver.thumbnailSmUrlFor(story.thumbnailImageUrl, story.thumbnailImageKey)
                } else {
                    imageUrlResolver.thumbnailSmUrlFor(snapshot?.thumbnailImageUrl, snapshot?.thumbnailImageKey)
                },
                lastStoryPreview = lastPreviewByChatId[chat.id].orEmpty(),
                // 턴 수는 persistTurn이 턴 저장과 원자적으로 증가시키는 비정규화 카운터를 그대로 읽는다.
                turnCount = chat.currentTurn,
                reachedEndings = libraryReachedEndingName(chat, showsCurrent, endingNameById, snapshot)
                    ?.let(::listOf)
                    .orEmpty(),
                updatedAt = chat.updatedAt,
            )
        }
    }

    /**
     * 도달 엔딩 이름(KNK-1065). 제목·프롤로그와 같은 규칙이다 — 읽을 수 있으면 현재 이름, 아니면 그 스토리가
     * 마지막으로 공개였던 시점의 스냅샷에서 이름을 찾는다. 서재(채팅 단위)와 상세·공유(턴 단위)가 이 하나를 공유한다.
     *
     * **두 분기의 폴백 조건이 다르다.** 뿌리는 "라이브 id와 스냅샷 id는 서로 다른 세계"라는 사실이다 —
     * 스토리 수정은 자식을 delete + re-insert하므로 이름이 같아도 id가 바뀐다.
     *
     * - **읽기 가능 분기**는 라이브 사전을 본다. 사전은 바로 그 id들로 조회해 만들고 FK가 행의 존재를
     *   보장하므로 조회가 빗나갈 수 없다 — 여기서 폴백을 허용해도 실제로 타지는 않는다. 그래도 좁게 두는 건
     *   방어다: 혹시 빗나가더라도 소유자가 공개 상태에서 바꾼 **현재** 이름을 도달 당시 이름으로 덮지 않는다.
     * - **스냅샷 분기**는 다른 세계의 id를 본다. 저장은 새 id로 됐는데 사전은 옛 id를 들고 있어 조회가 영영
     *   빗나가고, 좁은 조건이면 라벨이 통째로 사라진다. 그래서 **빗나가면 이름으로 떨어진다** — 저장 경로에서
     *   이미 "id가 아니라 이름으로 맞춘다"고 정한 규칙을 읽기에도 적용하는 것이다(PR #224 Codex P2 재리뷰).
     */
    private fun reachedEndingNameFor(
        endingId: Long?,
        showsCurrent: Boolean,
        endingNameById: Map<Long, String>,
        snapshot: StoryPublicSnapshot?,
        fallbackName: String?,
    ): String? {
        val id = endingId ?: return fallbackName
        return if (showsCurrent) {
            endingNameById[id]
        } else {
            snapshot?.endingNameById()?.get(id) ?: fallbackName
        }
    }

    /**
     * 서재 카드의 도달 엔딩 이름. [reachedEndingNameFor]와 같되, **참조가 끊긴 경우만** 채팅에 박아둔
     * 이름([StoryChat.reachedEndingNameSnapshot])으로 복구한다.
     *
     * 스토리 수정의 `endings[]`는 전체 교체라 행을 삭제·재생성하고, FK가 `ON DELETE SET NULL`이라
     * [StoryChat.reachedEndingId]가 비워진다. 스토리 스냅샷은 엔딩 id로 이름을 찾는 사전이라 조회 키가
     * 사라지면 덮을 수 없다. **비공개 스토리만의 문제가 아니다** — 공개 스토리에서 제작자가 엔딩을 손보기만
     * 해도 그 스토리로 놀던 모든 독자의 도달 기록이 사라진다.
     *
     * 턴 단위(상세·공유)도 이제 같은 방식으로 복구한다 — `story_messages`에 도달 시점 이름을 함께 박아
     * **그 컬럼이 도달 턴 표식 역할**을 하기 때문이다(PR #224 Codex P2 재리뷰). 단, **이 배포 이후 새로
     * 도달하는 건만** 그렇다. 배포 전에 이미 참조가 끊긴 과거 도달은 어느 턴이었는지조차 남아 있지 않아
     * 서재만 살아나는 비대칭이 그대로다.
     */
    private fun libraryReachedEndingName(
        chat: StoryChat,
        showsCurrent: Boolean,
        endingNameById: Map<Long, String>,
        snapshot: StoryPublicSnapshot?,
    ): String? {
        return reachedEndingNameFor(
            chat.reachedEndingId,
            showsCurrent,
            endingNameById,
            snapshot,
            fallbackName = chat.reachedEndingNameSnapshot,
        )
    }

    /**
     * 읽을 수 없는 스토리의 프롤로그 폴백(PR #224 Codex P2).
     *
     * 스토리 스냅샷은 시작 설정을 **id로** 찾는데, 소유자가 편집 폼에서 시작 설정 항목을 빼면
     * `story_start_settings` 행이 삭제되고 FK(`ON DELETE SET NULL`)가 [StoryChat.startSettingId]를 비운다.
     * 조회 키가 사라지므로 사전으로는 덮을 수 없다 — 도달 엔딩 이름과 **완전히 같은 구조**의 파괴다.
     * 그래서 같은 방식으로, **참조가 끊긴 경우만** 채팅에 박아둔 프롤로그로 복구한다.
     *
     * 조건을 좁게 잡는다: `start_setting_id`가 살아 있으면 기존대로 스냅샷 사전을 본다. 넓히면(사전에서 못
     * 찾을 때마다 폴백) 공개 상태에서 프롤로그를 고친 정상 케이스까지 채팅 생성 시점 값으로 되돌린다.
     *
     * **부분 복구다.** AI 조립의 `startSettings`에는 프롤로그 말고 이름·시작 상황·추천 입력도 있는데 채팅
     * 컬럼에는 프롤로그뿐이라 나머지는 빈 값으로 남는다(엔딩 후보도 시작 설정 스코프라 비어 있다).
     */
    private fun brokenReferencePrologue(chat: StoryChat): String? =
        chat.storyPrologueSnapshot.takeIf { chat.startSettingId == null }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getChatDetail(chatId: String, userId: Long?): ChatDetailResponse {
        val chat = resolveChat(chatId)
        // 소유권 게이트(§4-5, KNK-480): 소유 채팅은 소유자만, 게스트 채팅은 게스트만 조회한다.
        // 존재 판정(resolveChat의 404) 뒤에 적용해, 회원의 게스트 채팅 열람·타인 소유 채팅 열람을 403으로 막는다.
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 조회할 권한이 없습니다.")
        }

        val story = storyRepository.findById(chat.storyId).orElse(null)
        // 서재와 같은 스냅샷 규칙(KNK-1059·1065). 여기 요청자는 위 게이트를 통과한 채팅 소유자이지만,
        // 스토리 소유자와는 별개라 남의 스토리가 비공개로 돌아가면 그 뒤 제목·프롤로그가 보여선 안 된다.
        val showsCurrentStory = story?.isCurrentMetadataVisibleTo(userId) == true
        // 읽을 수 없으면 그 스토리가 마지막으로 공개였던 시점의 스냅샷에서 멈춘다(KNK-1065).
        val snapshot = if (showsCurrentStory) null else story?.let { storyPublicSnapshotService.findByStoryId(it.id) }
        val storyTitle = (if (showsCurrentStory) story.title else snapshot?.title).orEmpty()
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
        //
        // 스토리를 읽을 수 없으면 추천 입력도 내리지 않는다(PR #220 Codex P1). 제목·프롤로그와 같은 유출인데
        // 여기만 **스냅샷이 아니라 게이트로** 막는다 — 추천 입력은 값 하나가 아니라 목록이라 스냅샷하려면
        // JSON 컬럼이나 별도 테이블이 필요한 반면, 입력을 돕는 보조 장치라 없어도 채팅이 성립하기 때문이다.
        val suggestedInputs = if (showsCurrentStory && turns.isEmpty()) {
            loadSuggestedInputs(startSetting?.id)
        } else {
            emptyList()
        }

        return ChatDetailResponse(
            id = chat.publicId.toString(),
            storyId = story?.publicId?.toString().orEmpty(),
            storyTitle = storyTitle,
            prologue = (
                if (showsCurrentStory) {
                    startSetting?.prologue
                } else {
                    snapshot?.startSettingOf(chat.startSettingId)?.prologue ?: brokenReferencePrologue(chat)
                }
                ).orEmpty(),
            turns = turns.map { assistant ->
                ChatTurnResponse(
                    id = assistant.id,
                    userInput = assistant.userInput,
                    aiOutput = assistant.content,
                    choices = choicesByMessageId[assistant.id].orEmpty(),
                    reachedEnding = reachedEndingNameFor(
                        assistant.reachedEndingId,
                        showsCurrentStory,
                        endingNameById,
                        snapshot,
                        fallbackName = assistant.reachedEndingNameSnapshot,
                    ),
                    createdAt = assistant.createdAt,
                )
            },
            suggestedInputs = suggestedInputs,
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
     * 공유된 채팅을 조회한다(스펙 §4-3-11). **인증 불필요** — 추측 불가 공유 토큰 보유가 접근 수단이다.
     *
     * 형식 오류·부재·원본 채팅의 소프트 삭제를 모두 404로 통일해 존재 여부를 노출하지 않는다.
     *
     * 스토리 제목은 채팅 상세·서재와 같은 스냅샷 규칙을 탄다(KNK-1059·1065). 열람자는 링크만 가진 익명일 수 있으므로
     * [userId]는 알 수 있으면 그 값, 아니면 null이다. 기본값을 두지 않아 호출부가 매번 명시하게 한다 —
     * 새 호출부가 무심코 익명 판정으로 빠지면 읽을 수 있는 사용자에게까지 스냅샷이 나가기 때문이다.
     * 판정 결과는 공개 스토리면 현재 제목을 따라가고, 비공개로 되돌렸거나
     * 삭제됐으면 채팅 스냅샷에서 멈춘다. 이 게이트가 없으면 비공개로 돌린 스토리의 최신 제목이 링크를 가진
     * 아무에게나 보인다. 프롤로그(스토리 도입부 본문)도 같은 규칙을 탄다 — 제목보다 유출 폭이 커서다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getChatShare(shareId: String, userId: Long?): ChatShareResponse {
        val share = parsePublicIdOrNull(shareId)?.let { storyChatShareRepository.findByPublicId(it) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "공유를 찾을 수 없습니다.")
        // 공유에는 삭제 컬럼이 없다 — 유효성은 원본 채팅의 deleted_at에 종속된다(공유 해지 수단 = 채팅 삭제).
        val chat = storyChatRepository.findById(share.chatId).orElse(null)?.takeIf { it.deletedAt == null }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "공유를 찾을 수 없습니다.")

        val story = storyRepository.findById(chat.storyId).orElse(null)
        val startSetting = chat.startSettingId?.let { storyStartSettingRepository.findById(it).orElse(null) }
        val showsCurrentStory = story?.isCurrentMetadataVisibleTo(userId) == true
        val snapshot = if (showsCurrentStory) null else story?.let { storyPublicSnapshotService.findByStoryId(it.id) }

        // 커트라인 이하 턴만 싣는다. 발급 이후 진행분은 제외되고, 커트라인 이내 턴의 재생성 결과(활성본)는 반영된다.
        val turns = loadSharedTurns(chat.id, share.turnCutoff)

        // 도달 엔딩은 상세와 동일하게 이름으로 노출한다(순차 PK 노출 금지, KNK-462).
        val endingNameById = storyEndingRepository
            .findAllById(turns.mapNotNull { it.reachedEndingId })
            .associate { it.id to it.name }

        return ChatShareResponse(
            id = share.publicId.toString(),
            storyId = story?.publicId?.toString().orEmpty(),
            storyTitle = (if (showsCurrentStory) story.title else snapshot?.title).orEmpty(),
            prologue = (
                if (showsCurrentStory) {
                    startSetting?.prologue
                } else {
                    snapshot?.startSettingOf(chat.startSettingId)?.prologue ?: brokenReferencePrologue(chat)
                }
                ).orEmpty(),
            turns = turns.map { assistant ->
                ChatShareTurnResponse(
                    userInput = assistant.userInput,
                    aiOutput = assistant.content,
                    reachedEnding = reachedEndingNameFor(
                        assistant.reachedEndingId,
                        showsCurrentStory,
                        endingNameById,
                        snapshot,
                        fallbackName = assistant.reachedEndingNameSnapshot,
                    ),
                    createdAt = assistant.createdAt,
                )
            },
        )
    }

    /**
     * 공유 커트라인([turnCutoff]) 이하의 턴만 조립한다. 채팅 전체 메시지를 읽지 않아 로드량이 공유분에 비례한다(Codex P2).
     *
     * 커트라인 경계를 `message_order` 산술(턴 N = 2N)로 구하지 않는다 — 그 가정은 SYSTEM 메시지가 낀 형태
     * (SYSTEM order 1 + 턴 N이 order 2N·2N+1, `ChatStreamHistoryIntegrationTests`가 지원 형태로 고정)에서 깨져
     * 마지막 턴이 통째로 누락된다. 대신 **N번째 ASSISTANT 메시지의 order**를 논리 턴 기준으로 구해 그 이하만 읽으므로,
     * 앞에 몇 건이 끼든 순서에 구멍이 있든 결과가 같다.
     */
    private fun loadSharedTurns(chatPk: Long, turnCutoff: Int): List<PairedTurn> {
        if (turnCutoff <= 0) {
            return emptyList()
        }
        val cutoffOrder = storyMessageRepository
            .findByChatIdAndRoleOrderByMessageOrderAsc(chatPk, MessageRole.ASSISTANT, PageRequest.of(turnCutoff - 1, 1))
            .firstOrNull()
            ?.messageOrder
        // 커트라인보다 턴이 적은 채팅(정상 경로에선 없는 데이터 이상)은 있는 만큼만 싣는다.
            ?: return pairTurns(storyMessageRepository.findByChatIdOrderByMessageOrderAsc(chatPk)).take(turnCutoff)

        // pairTurns·take는 페어링과 턴 상한을 한 번 더 보장한다(짝 없는 USER·SYSTEM은 턴에서 제외).
        return pairTurns(
            storyMessageRepository.findByChatIdAndMessageOrderLessThanEqualOrderByMessageOrderAsc(chatPk, cutoffOrder),
        ).take(turnCutoff)
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
                            // 도달 엔딩은 ASSISTANT 메시지에 표식된다(reached_ending_id + 이름 스냅샷). 상세에서 이름으로 해소한다.
                            reachedEndingId = message.reachedEndingId,
                            reachedEndingNameSnapshot = message.reachedEndingNameSnapshot,
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
        // 도달 시점 이름. reachedEndingId가 FK로 비워져도 남는 **도달 턴 표식**이다.
        val reachedEndingNameSnapshot: String?,
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
        // 선택 기록의 세대 가드 기준 시각(KNK-819). **AI 호출 전인 요청 진입 시점**에 잡아야 한다 — 사용자는
        // 요청을 보내기 전에 선택지를 봤으므로, 이 시각 이후에 만들어진 선택지 행은 재생성이 갈아끼운 세대다.
        val requestedAt = Instant.now()
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
        // 이어쓰기가 만들 턴 번호의 예측치는 current_turn + 1이다(권위값은 저장이 확정하는 ai_call_logs.turn_number).
        val aiCall = assembleAiRequest(
            chat = chat,
            userInput = request.userInput,
            turnNumber = chat.currentTurn + 1,
            userSource = request.userSource,
        )

        return streamTurnInternal(
            chatId = chatId,
            chat = chat,
            storyPublicId = storyPublicId,
            aiCall = aiCall,
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
                    // 선택 기록(KNK-819)은 프론트가 보낸 값을 그대로 넘긴다 — 유효성 판정은 저장 트랜잭션 안에서 한다.
                    // 여기서 미리 거르면 락 밖 검사가 되어, AI 호출(최대 180초) 동안 마지막 턴이 바뀐 경우를 놓친다.
                    // requestedAt은 그 판정의 세대 기준이다(위 진입 시점에 확정).
                    selection = ChoiceSelection(request.sourceTurnId, request.choiceOrder, requestedAt),
                    // 조립이 AI에게 보낸 그 목록. 저장 판정이 같은 출처를 봐야 비공개 전환 후 교체된
                    // 스토리에서도 도달·사건이 누락되지 않는다(PR #224 Codex P2).
                    judgmentSource = aiCall.judgmentSource,
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
        // 재생성은 새 턴을 만들지 않고 마지막 턴을 교체하므로, 대상 턴 번호는 current_turn 그대로다(+1 아님).
        val aiCall = buildAiRequest(
            chat = chat,
            history = target.history,
            userInput = target.userInput,
            turnNumber = chat.currentTurn,
            isRegenerated = true,
        )

        return streamTurnInternal(
            chatId = chatId,
            chat = chat,
            storyPublicId = storyPublicId,
            aiCall = aiCall,
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

        // 선택지는 이미 저장된 마지막 턴에 붙으므로 대상 턴 번호는 current_turn이다(새 턴을 만들지 않는다).
        val aiCall = buildAiRequest(
            chat = chat,
            history = target.history,
            userInput = target.userInput,
            turnNumber = chat.currentTurn,
            isRegenerated = false,
        )

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
        aiCall: AiTurnCall,
        userId: Long?,
        deviceId: String?,
        isRegenerated: Boolean,
        persist: (ChatTurnAiResult) -> ChatTurnPersister.PersistedTurn,
        onPersisted: (ChatTurnPersister.PersistedTurn, Long) -> Unit,
    ): SseEmitter {
        val chatPk = chat.id
        // 소모 정책값은 이 턴 안에서 한 번만 읽어 차감·환불에 같은 값을 쓴다(KNK-1056). 차감과 환불 사이에
        // 정책이 바뀌면 금액이 어긋나 사용자가 손해를 보거나 이득을 본다(환불은 원장 행이 아니라 이 값을 쓴다).
        val chatTurnCost = creditPolicyService.amountOf(CreditPolicyKey.CHAT_TURN_COST)
        // 선차감·한도 예약은 SseEmitter를 만들기 전 동기 구간이다. 여기서 나는 402/400은 AI를 부르기 전 거부라
        // AI 타이머(manyak.ai.call.duration)에도 Langfuse에도 남지 않으므로 rejected로 세어 둔다(KNK-811).
        val (memberTrialCovered, guestDeviceId) = try {
            // 회원이면 SseEmitter를 만들기 전에 동기로 선차감한다. 잔액 부족 시 여기서 InsufficientCreditException이
            // 던져져(스트림 미개시) 컨트롤러가 402로 변환한다.
            // 위 소유권 가드 뒤이므로, owned 채팅이면 요청자 == 소유자 ⇒ 소유자가 차감되고,
            // 게스트 채팅을 회원이 이어쓰면 그 회원이 차감된다(게스트는 userId == null이라 무차감).
            // 회원 소모 2단(스펙 §4-3-7 B13): 계정 귀속 체험 잔여가 있으면 먼저 무료로 소진하고, 없으면 크레딧을 선차감한다.
            val covered =
                userId != null && guestTrialLimitService.reserveMember(userId, GuestTrialLimitService.Counter.CHAT_TURN)
            if (userId != null && !covered) {
                creditWalletService.deduct(userId, chatTurnCost, CreditReason.CHAT_TURN, refType = "CHAT", refId = chatPk)
            }
            // 게스트(userId == null)는 크레딧 대신 디바이스 ID별 chat_turn 체험 한도를 예약한다(스펙 §4-3-7, KNK-477).
            // 한도 소진·device 헤더 누락은 여기서 동기 402/400으로 던져져(스트림 미개시) 그대로 컨트롤러에 전파된다.
            covered to guestTrialLimitService.reserveForGuestOrNull(userId, deviceId, GuestTrialLimitService.Counter.CHAT_TURN)
        } catch (throwable: Throwable) {
            // 4xx 거부만 rejected다. Redis·DB 장애로 예약·차감이 깨진 경우는 5xx로 나가므로 failure로 세야
            // 실패 알림에 잡힌다 — rejected는 알림에서 제외되는 축이라 여기 섞으면 운영 장애가 조용히 사라진다(Codex P2).
            recordChatTurnResult(if (isChatTurnClientRejection(throwable)) OUTCOME_REJECTED else OUTCOME_FAILURE)
            throw throwable
        }
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
                    chatTurnAiClient.streamTurn(
                        aiCall.request,
                        aiCall.traceLink,
                        // AI가 스트리밍 중 보내는 인물 이미지 이벤트를 그대로 중계한다(KNK-943).
                        // 검증·변환은 하지 않는다 — 매핑 자체를 백엔드가 보냈으므로 돌아온 URL은 이미 검증된 값이다.
                        onCharacterImage = { characterImage ->
                            if (!Thread.currentThread().isInterrupted) {
                                emitter.send(
                                    SseEmitter.event()
                                        .name("character_image")
                                        .data(
                                            ChatStreamCharacterImageEvent(
                                                name = characterImage.name,
                                                imageUrl = characterImage.imageUrl,
                                            ),
                                        ),
                                )
                            }
                        },
                    ) { token ->
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
                // 선택지는 턴 스트림에서 채우지 않는다(스펙 §4-3-3, B23): completed.choices는 항상 빈 배열이고 저장도 빈 상태로 시작한다.
                // 프론트가 전용 트리거 엔드포인트(/turns/{turnId}/choices)로 선택지를 생성·저장한다(KNK-625 분리). 이로써 completed가
                // 선택지 생성(90초)을 기다리지 않아 지연 이득을 회복한다. AI 계약상 turn 결과의 choices는 빈 배열이나, 계약을 확정적으로
                // 유지하려 여기서 명시적으로 비운다(stub 등 잔여 값 방지).
                val result = recorded.result.copy(choices = emptyList())
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
                val persistedOk = persisted.get()
                // 결과 판정은 이 finally·아래 whenComplete·스케줄 거부 catch 셋뿐이고 서로 배타적이다(KNK-811).
                // 워커가 실행된 경우는 여기가 소유하므로 아래 whenComplete는 workerStarted로 걸러진다.
                recordChatTurnResult(if (persistedOk) OUTCOME_SUCCESS else OUTCOME_FAILURE)
                if (!persistedOk) {
                    refundChatTurn(userId, guestDeviceId, memberTrialCovered, chatPk, refundKey, refundGate, chatTurnCost)
                }
            }
        }, chatSseExecutor)
        } catch (rejected: Throwable) {
            // 스케줄 거부(chatSseExecutor 포화 시 RejectedExecutionException 등)는 위 async 블록이 실행되지 않아
            // 그 catch·onCompletion 환불이 돌지 않는다. 이미 선차감했으므로 여기서 환불한 뒤(gate로 1회) 예외를
            // 그대로 올려 호출자에게 실패로 드러낸다. 스트림은 열리지 않았으니 emitter를 오류로 닫아 반쯤 열린 상태를 막는다(Codex P1).
            // future가 만들어지지 않아 워커 finally도 아래 whenComplete도 돌지 않는다. 여기서만 센다(KNK-811).
            recordChatTurnResult(OUTCOME_FAILURE)
            refundChatTurn(userId, guestDeviceId, memberTrialCovered, chatPk, refundKey, refundGate, chatTurnCost)
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
                // 큐드-취소는 별도 outcome으로 센다(KNK-811, Codex P2 재리뷰). `!workerStarted`는 확정이 아니라
                // 잠정 판정이다 — AsyncRun이 취소 검사를 통과한 뒤 람다가 workerStarted를 세우기 전 취소가
                // 끼어들면, 여기서 false를 보고도 워커가 곧이어 저장에 성공할 수 있다.
                //
                // 그래서 success/failure와 **같은 값을 쓰지 않는다**. 경합이 나면 이 턴은 `cancelled` 1건과
                // 워커의 `success` 1건이 함께 남아 합계만 하나 늘 뿐, **저장·과금된 턴이 실패로 굳지 않는다**.
                // 잠정 판정을 failure로 세고 게이트로 잠그면 그 오분류가 영구히 남는다(그쪽이 더 나쁘다).
                recordChatTurnResult(OUTCOME_CANCELLED)
                refundChatTurn(userId, guestDeviceId, memberTrialCovered, chatPk, refundKey, refundGate, chatTurnCost)
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
        // 차감 때 쓴 금액. 환불은 반드시 같은 값이어야 한다(KNK-1056).
        amount: Long,
    ) {
        if (userId == null && guestDeviceId == null) return
        if (!gate.compareAndSet(false, true)) return
        try {
            val restored = if (memberTrialCovered && userId != null) {
                // 이 턴이 체험 잔여로 무료 처리됐으면 크레딧이 아니라 회원 체험 카운터를 되돌린다(스펙 §4-3-7 B13).
                // restoreMember는 Redis 장애를 삼키고 정상 반환하므로, 성공 여부는 반환값으로만 알 수 있다(Codex P2).
                guestTrialLimitService.restoreMember(userId, GuestTrialLimitService.Counter.CHAT_TURN)
            } else if (userId != null) {
                creditWalletService.reward(
                    userId = userId,
                    amount = amount,
                    reason = CreditReason.REFUND,
                    idempotencyKey = refundKey,
                    refType = "CHAT",
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
     * outcome은 스토리 완성·스토리라인과 같은 3값이다:
     *   - `success`  : 턴이 저장까지 확정된 호출(`persisted`)
     *   - `failure`  : 저장에 도달하지 못하고 끝난 호출 — AI 실패·타임아웃·저장 오류·큐드 취소·스케줄 거부
     *   - `rejected` : 스트림 개시 **이전** 4xx — 크레딧 부족(402)·게스트 한도 소진(402)·device 헤더 누락(400)
     *
     * **Timer가 아니라 Counter인 이유**: 채팅 턴은 스트리밍이라 `manyak.ai.call.duration{feature="chat_response"}`이
     * 이미 스트림 전체를 재고 있어 유스케이스 타이머가 사실상 겹친다. 반면 `rejected`는 AI를 부르기 전에 끊겨
     * 그쪽에 남지 않는다.
     *
     * **호출 지점은 셋이다**:
     *   1. 워커 finally — 워커가 실행된 모든 경우를 소유하며 `persisted`로 success/failure를 가른다
     *   2. 스케줄 거부 catch — future 자체가 만들어지지 않아 워커가 확실히 없는 경우(failure)
     *   3. `future.whenComplete`의 `!workerStarted` — 큐드-취소(`cancelled`)
     *
     * 3번만 **잠정 판정**이다. AsyncRun이 취소 검사를 통과한 뒤 람다가 `workerStarted`를 세우기 전 취소가
     * 끼어들면, 그 시점의 `false`를 보고도 워커가 곧이어 저장에 성공할 수 있다. 그래서 3번은 1·2와
     * **다른 outcome 값**을 쓴다 — 경합이 나면 `cancelled` 1건과 `success` 1건이 함께 남아 합계만 하나 늘 뿐,
     * 저장·과금된 턴이 실패로 굳지 않는다. 잠정 판정을 `failure`로 세고 1회 게이트로 잠그면 그 오분류가
     * 영구히 남는데, 그쪽이 더 나쁘다(Codex P2 재리뷰에서 확인).
     */
    private fun recordChatTurnResult(outcome: String) {
        // 관측 실패가 SSE 종료나 환불을 막지 않도록 격리한다.
        runCatching {
            Counter.builder("manyak.chat.turn.result")
                .description("채팅 턴 결과 분포")
                .tag("outcome", outcome)
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

    /**
     * 이미 검증된 채팅으로 AI 채팅 턴 요청 재료를 조립한다.
     * 오프닝은 [ChatTurnStartSettings]로만 전달하고 history에는 포함하지 않으며,
     * 현재 입력은 아직 저장 전이므로 history에 들어가지 않는다.
     */
    private fun assembleAiRequest(
        chat: StoryChat,
        userInput: String,
        turnNumber: Int,
        userSource: String?,
    ): AiTurnCall =
        buildAiRequest(
            chat = chat,
            history = assembleHistory(chat.id),
            userInput = userInput,
            turnNumber = turnNumber,
            isRegenerated = false,
            userSource = userSource,
        )

    /**
     * 스토리 조립 재료(설정·시작 설정·주요 사건·엔딩·장르)와 주어진 [history]·[userInput]으로 AI 채팅 턴 요청을 조립한다.
     * 이어쓰기는 전체 내역을, 재생성(§4-3-9)은 마지막 턴을 제외한 내역을 [history]로 넘긴다.
     *
     * AI 호출 헤더로 나갈 연결 식별자([AiTraceLink], KNK-751)도 이 안에서 함께 만든다 — 조립과 같은 트랜잭션에서
     * 읽어야 게이트와 다른 시점을 보지 않기 때문이다.
     */
    private fun buildAiRequest(
        chat: StoryChat,
        history: List<ChatHistoryMessage>,
        userInput: String,
        // 이번 호출이 만들 턴 번호의 **예측치**다(이어쓰기 current_turn + 1, 재생성·선택지는 그 턴 자체인 current_turn).
        // 권위값이 아니며 최종 대조는 ai_call_logs.turn_number가 한다(번호 선점은 하지 않는다 — 실패가 번호를 태우면 안 됨).
        turnNumber: Int,
        isRegenerated: Boolean,
        userSource: String? = null,
    ): AiTurnCall = assemblyTransactionTemplate.execute {
        // 스토리를 여기서 **다시** 읽는다. 호출부가 읽어둔 값을 쓰면 게이트 판정만 트랜잭션 밖 시점이 되어
        // 자식 데이터와 다른 스냅샷을 보게 된다(Codex P2). 조립에 필요한 스토리 파생 값을 전부 이 안에서 읽는다.
        val story = storyRepository.findById(chat.storyId).orElse(null)
        // 조립 재료는 생성 결과를 통해 독자에게 전달되므로 읽기 응답과 같은 규칙을 탄다(KNK-1064·1065).
        // 판정 주체는 **턴을 돌리는 사람**이고, 세 진입점(이어쓰기·재생성·선택지)이 모두 앞에서 requireChatOwner로
        // 요청자 == 채팅 소유자를 강제하므로 chat.userId가 곧 그 사람이다(게스트 채팅은 null).
        // 공개 스토리면 현재 값을 쓴다 — 제작자의 밸런스 패치가 진행 중인 채팅에도 반영돼야 한다. 비공개로
        // 되돌렸거나 삭제됐으면 마지막 공개 버전 스냅샷이다 — 화면에서 막아놓은 개작이 생성 결과로 새면 안 된다.
        //
        // 두 경우를 **같은 모양(StoryPublicSnapshot)** 으로 만들어 아래 조립이 한 갈래로 흐르게 한다. 갈래를
        // 나누면 한쪽만 고치는 순간 유출이 되살아난다 — KNK-1059가 프롤로그만 막고 설정·사건·엔딩을 놓친 방식이다.
        val showsCurrentStory = story?.isCurrentMetadataVisibleTo(chat.userId) == true
        val material = if (showsCurrentStory) {
            story?.let(storyPublicSnapshotService::capture)
        } else {
            // 스냅샷이 NULL이면(한 번도 공개된 적 없거나 V69 백필 대상 밖) 재료가 통째로 빈다. 현재 값으로
            // 되돌리지 않는다 — 그 폴백은 막으려는 유출을 정확히 그 케이스에서 되살리고, AI에는 대화 내역이
            // 함께 가므로 재료가 비는 손해가 비공개 개작을 흘리는 손해보다 작다.
            story?.let { storyPublicSnapshotService.findByStoryId(it.id) }
        }
        val startSetting = material?.startSettingOf(chat.startSettingId)
        val mainEvents = material?.mainEvents.orEmpty()

        // 주요 사건 런타임 상태(§4-3-10, D11). AI가 무상태이므로 백엔드가 매 턴 되돌려 싣는다.
        //
        // 목표는 **라이브 id**로 저장되는데([ChatTurnPersister.applyMainEventState]) 이 목록은 스냅샷일 수
        // 있다. 스토리 수정이 자식을 delete + re-insert해 id를 갈아치우므로, id로만 비교하면 매 턴 빗나가
        // 목표와 진행 턴 수가 계속 사라진다(PR #224 Codex P2 재리뷰). id가 맞으면 그대로 쓰고(읽기 가능
        // 분기·미변경 스냅샷은 여기서 끝난다), 빗나가면 라이브 행에서 이름을 얻어 **이름으로** 맞춘다.
        val targetMainEvent = chat.targetMainEventId?.let { targetId ->
            (
                mainEvents.firstOrNull { it.id == targetId }
                    ?: storyMainEventRepository.findById(targetId).orElse(null)
                        ?.let { live -> mainEvents.firstOrNull { it.name == live.name } }
                )
                ?.let { ChatTurnTargetMainEvent(name = it.name, progressTurns = chat.targetProgressTurns) }
        }
        // 요청에 싣는 그 목록 그대로를 저장 판정으로 넘긴다(PR #224 Codex P2).
        val endings = eligibleEndings(chat, startSetting)

        AiTurnCall(
            request = ChatTurnAiRequest(
                genre = material?.genre.orEmpty(),
                storySettings = ChatTurnStorySettings(
                    worldSetting = material?.storySettings?.worldSetting.orEmpty(),
                    characterSetting = material?.storySettings?.characterSetting.orEmpty(),
                    userRoleSetting = material?.storySettings?.userRoleSetting.orEmpty(),
                    ruleSetting = material?.storySettings?.ruleSetting.orEmpty(),
                ),
                startSettings = ChatTurnStartSettings(
                    name = startSetting?.name.orEmpty(),
                    // 시작 설정 참조가 끊겼으면(항목 삭제 → FK로 start_setting_id NULL) 채팅에 박아둔
                    // 프롤로그로 복구한다. 이름·시작 상황은 채팅에 없어 빈 값으로 남는 부분 복구다.
                    prologue = (
                        startSetting?.prologue
                            ?: if (showsCurrentStory) null else brokenReferencePrologue(chat)
                        ).orEmpty(),
                    startSituation = startSetting?.startSituation.orEmpty(),
                ),
                history = history,
                userInput = userInput,
                summary = "",
                // 인물-이미지 매핑(KNK-943). 이어쓰기·재생성·선택지 생성이 이 조립을 공유하므로 세 경로 모두 같은 매핑을 싣는다.
                // 스냅샷 대상이 아니다 — story_characters는 전 컬럼이 불변이고 수정 API가 손대지 않아 개작될 수 없다.
                characterImages = loadCharacterImages(chat.storyId),
                userSource = userSource,
                mainEvents = mainEvents.map { ChatTurnMainEvent(it.name, it.description, it.keySentence) },
                targetMainEvent = targetMainEvent,
                occurredMainEventNames = resolveOccurredMainEventNames(chat, mainEvents),
                endings = endings.map { ChatTurnEnding(it.name, it.achievementCondition, it.epilogue) },
            ),
            traceLink = AiTraceLink(
                // 간편 제작 스토리만 값이 있다(채팅 생성 시 1회 해석해 박아 둔 값). 일반 제작은 null이라 헤더가 생략된다.
                creationId = chat.creationId,
                storyId = story?.publicId,
                chatId = chat.publicId,
                // trace 연결용 식별자는 스냅샷이 아니라 실제 행에서 읽는다 — 열람자에게 가는 값이 아니라
                // Langfuse에서 이 턴이 어느 시작 설정 행을 탔는지 되짚는 관측 키다.
                startSettingId = chat.startSettingId
                    ?.let { storyStartSettingRepository.findById(it).orElse(null) }
                    ?.publicId,
                turnNumber = turnNumber,
                isRegenerated = isRegenerated,
            ),
            judgmentSource = TurnJudgmentSource(endings = endings, mainEvents = mainEvents),
        )
    } ?: error("AI 턴 요청 조립이 결과 없이 끝났습니다: chatId=${chat.id}")

    /** AI 채팅 턴 호출 한 번에 필요한 요청 본문과 연결 식별자 헤더 재료(KNK-751). */
    private data class AiTurnCall(
        val request: ChatTurnAiRequest,
        val traceLink: AiTraceLink,
        /**
         * 이 요청에 **실제로 실어 보낸** 엔딩·주요 사건(PR #224 Codex P2). 저장 판정이 요청과 같은 출처를
         * 보게 하려고 조립 결과에 함께 실어 [ChatTurnPersister.persistTurn]까지 흘린다. 저장 시점에 다시
         * 읽으면 조립이 스냅샷을 봤는지 현재 값을 봤는지 알 수 없어 매칭이 갈라진다.
         */
        val judgmentSource: TurnJudgmentSource,
    )

    /**
     * 채팅 요청에 실을 인물-이미지 매핑을 조회한다(스펙 §4-3-3 "채팅 인물 이미지 전달", KNK-943).
     *
     * 이미지가 없는 인물(생성·업로드 실패로 `image_url`이 NULL)은 조회에서 제외한다 — AI는 이 매핑에 있는 인물만
     * 태그로 만들 수 있으므로, 매핑에 없으면 태그가 삭제되고 이미지 없이 본문만 나간다.
     * 인물이 없거나 전부 이미지가 없으면 빈 배열이며, 그때 AI는 인물 태그 규칙을 쓰지 않는다.
     */
    private fun loadCharacterImages(storyId: Long): List<ChatCharacterImage> =
        storyCharacterRepository.findByStoryIdAndImageUrlIsNotNullOrderByIdAsc(storyId)
            .mapNotNull { character ->
                character.imageUrl?.let { ChatCharacterImage(name = character.name, imageUrl = it) }
            }

    /**
     * 채팅이 거쳐온(완결) 사건 이름을 주요 사건 표시 순서로 반환한다(occurred_main_event_names 재료).
     *
     * 정본은 조인 테이블(`story_chat_main_events`)이지만, 그 `main_event_id`가 `story_main_events` FK
     * **ON DELETE CASCADE**라 소유자가 주요 사건을 교체하면 행이 통째로 사라진다. 그러면 AI에게 옛 사건을
     * 후보로 보내면서 "이미 완결했다"는 사실은 못 보내, 독자가 **이미 지난 사건을 다시 겪는다**(PR #224 Codex P2).
     * 그래서 채팅에 박아둔 이름 스냅샷과 **합집합**으로 채운다.
     *
     * 합집합인 이유: 조인 행만 보면 CASCADE 삭제를 못 살리고, 이름 스냅샷만 보면 소유자가 공개 상태에서
     * 사건 이름을 바꾼 경우(조인 행은 살아 있고 새 이름이 정답)를 놓친다.
     *
     * 결과는 항상 [mainEvents](이번 요청에 실은 목록)로 걸러 그 표시 순서로 낸다 — AI가 모르는 이름을
     * "이미 완결했다"고 보내면 판정이 엉킨다.
     */
    private fun resolveOccurredMainEventNames(chat: StoryChat, mainEvents: List<MainEventSnapshot>): List<String> {
        if (mainEvents.isEmpty()) {
            return emptyList()
        }
        // 이 id 비교는 **읽기 가능 분기에서만** 기여한다. 스냅샷 분기에서는 조인 행의 라이브 id와 스냅샷 id가
        // 다른 세계라 맞지 않는데, 사건이 교체되면 FK(ON DELETE CASCADE)가 조인 행을 함께 지워 occurredIds가
        // 비고 아래 이름 합집합이 덮는다. 그래서 틀린 결과가 나오지 않는다(PR #224 Codex P2 전수 확인).
        val occurredIds = storyChatMainEventRepository.findByChatId(chat.id).map { it.mainEventId }.toSet()
        val occurredNames = mainEvents.filter { it.id in occurredIds }.map { it.name }
            .plus(chat.occurredMainEventNamesSnapshot.orEmpty())
            .toSet()
        return mainEvents.filter { it.name in occurredNames }.map { it.name }
    }

    /**
     * 이번 턴 도달 후보 엔딩만 싣는다: 이미 도달한 채팅(reached_ending_id != null)이면 빈 목록(재판정 차단),
     * 그 외엔 이번 턴(current_turn + 1)이 최소 턴 수를 충족하는 엔딩만. 최소 턴 수 판정은 백엔드 결정 몫(§4-3-10).
     *
     * [startSetting]은 조립 재료(현재 값 또는 마지막 공개 버전 스냅샷)에서 온다. 스냅샷은 활성 엔딩만 담으므로
     * 라이브 조회(`enabled = true`)와 결과가 같다([StoryPublicSnapshotService.capture]).
     */
    private fun eligibleEndings(chat: StoryChat, startSetting: StartSettingSnapshot?): List<EndingSnapshot> {
        // 최초 1회 가드. id가 비어도(엔딩 행 교체로 id 없이 기록된 도달) 이름 스냅샷이 남으므로 둘 다 본다.
        if (chat.reachedEndingId != null || chat.reachedEndingNameSnapshot != null) {
            return emptyList()
        }
        val turnBeingGenerated = chat.currentTurn + 1
        return startSetting?.endings.orEmpty().filter { it.minTurns <= turnBeingGenerated }
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

        // SseEmitter 전체(MVC async) 상한. 턴 스트림은 본문 스트리밍 뒤 곧바로 completed를 보낸다(선택지는 전용 엔드포인트로
        // 분리 — B23). stopgap 동안 본문+내부 선택지 호출까지 덮으려 160s로 상향했던 값을 낮추되, AI 스트림의 이벤트 간 idle
        // 타임아웃(manyak.ai.chat.stream-timeout, 기본 60s)과 같게 두지 않는다: idle은 토큰 간격 상한이라 토큰이 계속 오면
        // 총 스트리밍이 60s를 넘길 수 있고, 전체 상한이 그와 같으면 정상적인 긴 턴이 completed 전에 잘려 클라이언트가 turnId를
        // 잃고 과금될 수 있다(Codex P2). idle 예산 위로 여유(2배)를 둬 헬시한 긴 스트림을 끊지 않는다.
        // (정상 턴은 완료 즉시 emitter.complete()로 조기 종료하므로, 이 값은 지연·행 상황의 비상 상한일 뿐이다.)
        const val SSE_TIMEOUT_MILLIS = 120_000L

        // [ChatTurnRefundMeters]가 기동 시 이 이름으로 시계열을 0에 만들어 둔다. 사전 등록과 여기의
        // increment가 같은 미터를 가리켜야 하므로(이름·태그가 미터 신원이다) 리터럴을 쓰지 않는다.
        const val METRIC_CHAT_TURN_REFUND = "manyak.chat.turn.refund"

        // manyak.chat.turn.result·manyak.chat.turn.refund가 공유하는 outcome 태그 값(유한 enum —
        // 의미는 recordChatTurnResult·recordChatTurnRefund KDoc). 스토리 완성·스토리라인과 같은 어휘를 쓴다.
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_REJECTED = "rejected"

        // 큐드-취소 전용. success/failure와 섞지 않는 이유는 whenComplete의 판정이 잠정적이기 때문이다
        // (recordChatTurnResult KDoc 참고). 알림은 failure만 보므로 이 값이 늘어도 오발화하지 않는다.
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
