package com.knk.manyak.chat.service

import com.knk.manyak.chat.dto.BatchChatRequest
import com.knk.manyak.chat.dto.ChatDetailResponse
import com.knk.manyak.chat.dto.ChatPersonaResponse
import com.knk.manyak.chat.dto.ChatShareResponse
import com.knk.manyak.chat.dto.ChatShareTurnResponse
import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.dto.ChatTurnResponse
import com.knk.manyak.chat.dto.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryChatShareRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.security.isOwnerAccessAllowed
import com.knk.manyak.global.util.parsePublicIdOrNull
import com.knk.manyak.image.service.ImageUrlResolver
import com.knk.manyak.story.service.StoryPlayReader
import com.knk.manyak.story.service.StoryPublicSnapshotService
import com.knk.manyak.story.service.UsernameTokenRenderer.render
import com.knk.manyak.story.snapshot.StoryPublicSnapshot
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 채팅 읽기 4경로(배치·서재·상세·공유)(KNK-2055에서 [ChatService]에서 분리).
 *
 * 네 경로는 각자 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`로 게이트(스토리)와 표시 값을 한 스냅샷에서
 * 읽는다(KNK-1059). 클래스 레벨 `@Transactional`은 쓰지 않는다 — 읽기 경로마다 격리 수준을 명시해 두고
 * `ChatReadIsolationGuardTests`가 그 설정을 고정한다.
 */
@Service
class ChatQueryService(
    private val chatGenerationStore: ChatGenerationStore,
    private val storyPlayReader: StoryPlayReader,
    // 읽을 수 없는 스토리의 마지막 공개 버전 스냅샷(서재·상세·공유, KNK-1065).
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
    private val imageUrlResolver: ImageUrlResolver,
    private val storyChatRepository: StoryChatRepository,
    private val storyChatShareRepository: StoryChatShareRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val chatModelService: ChatModelService,
    private val structuredLogger: StructuredLogger,
) {

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
        val storiesByStoryId = storyPlayReader.findViews(chats.map { it.storyId }, userId)
        // 채팅별 마지막 ASSISTANT 메시지만 한 번의 쿼리로 조회해 프리뷰로 사용한다.
        val lastPreviewByChatId = storyMessageRepository
            .findLatestMessagesByChatIdsAndRole(chats.map { it.id }, MessageRole.ASSISTANT)
            .associate { it.chatId to it.content }
        // 채팅이 도달한 엔딩 이름을 한 번에 조회한다(엔딩은 이름으로 노출, KNK-462). 채팅당 최대 1개.
        val endingNameById = storyPlayReader.findEndingNames(chats.mapNotNull { it.reachedEndingId })
        // 스냅샷은 **읽을 수 없는 스토리에만** 필요하다. 별도 테이블이라 그 스토리들만 한 번에 조회한다
        // (스토리 엔티티에 붙어 있었다면 목록 전체가 이 JSON을 함께 읽었다 — PR #224 Codex P2).
        val snapshotByStoryId = storyPublicSnapshotService.findAllByStoryIds(
            storiesByStoryId.values.filterNot { it.currentMetadataVisible }.map { it.id },
        )
        return chats.map { chat ->
            val story = storiesByStoryId[chat.storyId]
            // 제목·썸네일은 요청자가 지금 그 스토리를 읽을 수 있을 때만 현재 값을 쓴다(KNK-1059).
            // 비공개로 되돌렸거나 삭제됐으면 채팅에 박아둔 스냅샷에서 멈춘다. storyId(public_id)는
            // 요청자가 원래 알던 값이라 그대로 둔다 — 새로 새는 정보가 없다.
            val showsCurrent = story?.currentMetadataVisible == true
            // 읽을 수 없으면 그 스토리가 **마지막으로 공개였던 시점**의 스냅샷에서 멈춘다(KNK-1065).
            val snapshot = if (showsCurrent) null else story?.let { snapshotByStoryId[it.id] }
            val defaultName = if (showsCurrent) story?.protagonistName else snapshot?.protagonistName
            val chatName = chat.personaNameSnapshot ?: defaultName
            ChatSummaryResponse(
                persona = chat.personaNameSnapshot?.let(::ChatPersonaResponse),
                id = chat.publicId.toString(),
                storyId = story?.publicId?.toString().orEmpty(),
                storyTitle = render((if (showsCurrent) story.title else snapshot?.title).orEmpty(), chatName),
                // 채팅 카드(46×62)도 목록과 같은 축소 변형을 공유한다(스펙 §4-3-9 반응형 변형).
                // 생성 표지(KNK-1069)와 프리셋 키의 2단 폴백은 ImageUrlResolver가 소유한다.
                // 읽을 수 없으면 URL과 기존 프리셋 키를 마지막 공개 버전 스냅샷에서 읽는다.
                // 비공개로 되돌린 스토리도 마지막 공개 당시의 표지를 유지하며, 둘 다 없으면 null이다.
                thumbnailUrlSm = if (showsCurrent) {
                    imageUrlResolver.visibleThumbnailSmUrlFor(
                        story.thumbnailImageUrl,
                        story.thumbnailImageKey,
                        story.thumbnailModerationStatus,
                    )
                } else {
                    // 공개 스냅샷에는 검수 상태가 없다 — 공개였던 시점의 표시값이라 그대로 쓴다(ImageModeration).
                    imageUrlResolver.visibleThumbnailSmUrlFor(
                        snapshot?.thumbnailImageUrl,
                        snapshot?.thumbnailImageKey,
                        null,
                    )
                },
                lastStoryPreview = lastPreviewByChatId[chat.id].orEmpty(),
                // 턴 수는 persistTurn이 턴 저장과 원자적으로 증가시키는 비정규화 카운터를 그대로 읽는다.
                turnCount = chat.currentTurn,
                reachedEndings = libraryReachedEndingName(chat, showsCurrent, endingNameById, snapshot)
                    ?.let { listOf(render(it, defaultName)) }
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


    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getChatDetail(chatId: String, userId: Long?): ChatDetailResponse {
        // PostgreSQL REPEATABLE_READ의 스냅샷은 트랜잭션 시작이 아니라 첫 SQL에서 잡힌다.
        // 커밋 후 해제되는 생성 중 키를 먼저 읽어야 키가 없을 때 DB에서 커밋된 턴이 보인다.
        // 형식 오류는 Redis를 조회하지 않고 아래 resolveChat의 기존 404 경로로 보낸다.
        val generatingTurn = parsePublicIdOrNull(chatId)?.toString()?.let { publicId ->
            try {
                chatGenerationStore.get(publicId)
            } catch (ex: Exception) {
                structuredLogger.event("chat_generation_read_failed", "chat_id" to publicId, "error" to ex.javaClass.simpleName)
                null
            }
        }
        val chat = storyChatRepository.resolveChat(chatId)
        // 소유권 게이트(§4-5, KNK-480): 소유 채팅은 소유자만, 게스트 채팅은 게스트만 조회한다.
        // 존재 판정(resolveChat의 404) 뒤에 적용해, 회원의 게스트 채팅 열람·타인 소유 채팅 열람을 403으로 막는다.
        if (!isOwnerAccessAllowed(chat.userId, userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "채팅을 조회할 권한이 없습니다.")
        }

        val story = storyPlayReader.findView(chat.storyId, userId)
        // 서재와 같은 스냅샷 규칙(KNK-1059·1065). 여기 요청자는 위 게이트를 통과한 채팅 소유자이지만,
        // 스토리 소유자와는 별개라 남의 스토리가 비공개로 돌아가면 그 뒤 제목·프롤로그가 보여선 안 된다.
        val showsCurrentStory = story?.currentMetadataVisible == true
        // 읽을 수 없으면 그 스토리가 마지막으로 공개였던 시점의 스냅샷에서 멈춘다(KNK-1065).
        val snapshot = if (showsCurrentStory) null else story?.let { storyPublicSnapshotService.findByStoryId(it.id) }
        val chatName = chat.personaNameSnapshot ?: if (showsCurrentStory) story?.protagonistName else snapshot?.protagonistName
        val storyTitle = (if (showsCurrentStory) story.title else snapshot?.title).orEmpty()
        // prologue와 추천 입력 모두 시작 설정에 종속되므로 한 번만 조회해 재사용한다.
        val startSetting = chat.startSettingId?.let { storyPlayReader.findStartSetting(it) }

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
        val endingNameById = storyPlayReader.findEndingNames(turns.mapNotNull { it.reachedEndingId })

        // 아직 한 번도 이어쓰지 않은 채팅(turns 비어 있음)만 시작 추천 입력을 채운다.
        // 진행 턴이 있으면 다음 행동은 마지막 턴의 choices로 안내하므로 조회를 생략하고 빈 배열로 둔다.
        //
        // 읽을 수 없는 스토리는 **제목·프롤로그와 같이 스냅샷에서** 낸다(PR #224 Codex P2). KNK-1059가 여기만
        // 게이트로 비웠던 근거는 "목록이라 스냅샷하려면 JSON 컬럼이 필요하다"였는데, KNK-1065의 스토리
        // 스냅샷이 바로 그 JSON이라 근거가 사라졌다. 비우면 개작 유출은 막지만 독자가 채팅을 시작할 때 보던
        // 안내가 통째로 사라진다 — 마지막 공개 시점 값으로 내는 편이 맞다.
        val suggestedInputs = when {
            turns.isNotEmpty() -> emptyList()
            showsCurrentStory -> storyPlayReader.loadSuggestedInputs(startSetting?.id)
            // 시작 설정 참조가 끊긴 채팅은 스냅샷에서 찾을 키가 없다(채팅에는 추천 입력 컬럼이 없어 폴백도 없다).
            else -> snapshot?.startSettingOf(chat.startSettingId)?.suggestedInputs.orEmpty()
        }

        return ChatDetailResponse(
            model = chatModelService.effective(chat.modelCode).code,
            longResponse = chat.longResponse,
            generatingTurn = generatingTurn,
            persona = chat.personaNameSnapshot?.let(::ChatPersonaResponse),
            id = chat.publicId.toString(),
            storyId = story?.publicId?.toString().orEmpty(),
            storyTitle = render(storyTitle, chatName),
            prologue = (
                if (showsCurrentStory) {
                    startSetting?.prologue?.let { render(it, chatName) }
                } else {
                    snapshot?.startSettingOf(chat.startSettingId)?.prologue?.let { render(it, chatName) } ?: brokenReferencePrologue(chat)
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
                    )?.let { render(it, chatName) },
                    createdAt = assistant.createdAt,
                )
            },
            suggestedInputs = suggestedInputs.map { render(it, chatName) },
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

        val story = storyPlayReader.findView(chat.storyId, userId)
        val startSetting = chat.startSettingId?.let { storyPlayReader.findStartSetting(it) }
        val showsCurrentStory = story?.currentMetadataVisible == true
        val snapshot = if (showsCurrentStory) null else story?.let { storyPublicSnapshotService.findByStoryId(it.id) }
        val chatName = chat.personaNameSnapshot ?: if (showsCurrentStory) story?.protagonistName else snapshot?.protagonistName

        // 커트라인 이하 턴만 싣는다. 발급 이후 진행분은 제외되고, 커트라인 이내 턴의 재생성 결과(활성본)는 반영된다.
        val turns = loadSharedTurns(chat.id, share.turnCutoff)

        // 도달 엔딩은 상세와 동일하게 이름으로 노출한다(순차 PK 노출 금지, KNK-462).
        val endingNameById = storyPlayReader.findEndingNames(turns.mapNotNull { it.reachedEndingId })

        return ChatShareResponse(
            id = share.publicId.toString(),
            storyId = story?.publicId?.toString().orEmpty(),
            storyTitle = render((if (showsCurrentStory) story.title else snapshot?.title).orEmpty(), chatName),
            prologue = (
                if (showsCurrentStory) {
                    startSetting?.prologue?.let { render(it, chatName) }
                } else {
                    snapshot?.startSettingOf(chat.startSettingId)?.prologue?.let { render(it, chatName) } ?: brokenReferencePrologue(chat)
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
                    )?.let { render(it, chatName) },
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
internal fun brokenReferencePrologue(chat: StoryChat): String? =
    chat.storyPrologueSnapshot.takeIf { chat.startSettingId == null }
