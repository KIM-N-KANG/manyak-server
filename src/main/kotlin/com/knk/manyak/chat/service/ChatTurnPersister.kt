package com.knk.manyak.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryChatMainEvent
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryMessageVersion
import com.knk.manyak.chat.repository.StoryChatMainEventRepository
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryMessageVersionRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.story.entity.EndingSnapshot
import com.knk.manyak.story.entity.MainEventSnapshot
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.text.Normalizer
import java.time.Instant

/**
 * 채팅 턴 완료 시점의 원자적 저장 책임만 가진다.
 *
 * USER·ASSISTANT 메시지와 선택지, current_turn 증가를 하나의 트랜잭션으로 묶는다.
 * [ChatService]의 비동기 스트리밍 스레드에서 호출되므로, self-invocation 프록시 우회를
 * 피하기 위해 [ChatService]와 분리된 별도 빈으로 둔다.
 */
@Component
class ChatTurnPersister(
    private val storyChatRepository: StoryChatRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
    private val storyMessageVersionRepository: StoryMessageVersionRepository,
    private val storyChatMainEventRepository: StoryChatMainEventRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val endingReachRecorder: EndingReachRecorder,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    // 이력 선택지 스냅샷 직렬화용. 컨텍스트 ObjectMapper 빈에 의존하지 않도록 로컬 인스턴스를 둔다(List<String> 직렬화만 사용).
    private val objectMapper = ObjectMapper()

    /**
     * 한 턴(USER 입력 + AI 출력 + 선택지)을 원자적으로 저장하고 current_turn을 1 증가시킨다.
     * 메시지 순서는 직전 마지막 순서 n에 이어 USER=n+1, ASSISTANT=n+2로 매긴다.
     *
     * @return 저장된 ASSISTANT 메시지 id(turnId)와 증가된 턴 번호(turnNumber)
     */
    @Transactional
    fun persistTurn(
        chatId: Long,
        userInput: String,
        aiOutput: String,
        choices: List<String>,
        // AI 판정 결과(목표 사건·완결 사건·도달 엔딩). 턴 저장과 같은 트랜잭션에서 채팅 상태에 반영한다(§4-3-10, D11).
        judgment: TurnJudgment = TurnJudgment(),
        // 사용자가 고른 직전 턴 선택지(§4-3-3, KNK-819). 프론트가 안 보내면 두 값이 null이라 기록하지 않는다.
        selection: ChoiceSelection = ChoiceSelection(),
        // AI에게 **실제로 보낸** 엔딩·주요 사건 목록(KNK-1065, PR #224 Codex P2). 기본값을 두지 않아 호출부가
        // 매번 명시하게 한다 — 빠뜨리면 후보가 비어 도달·사건 판정이 조용히 전부 누락된다.
        judgmentSource: TurnJudgmentSource,
    ): PersistedTurn {
        // 이어쓰기(append)도 재생성과 같은 채팅 락을 잡아 두 경로를 채팅 단위로 직렬화한다. 락이 없으면
        // append가 새 메시지를 먼저 insert한 뒤 story_chats UPDATE에서 블록되는 사이, 동시 재생성이 그 미커밋
        // 행을 못 봐(READ COMMITTED) 낡은 마지막 턴을 교체·과금하고 append가 뒤이어 커밋될 수 있다(Codex P2).
        val chat = storyChatRepository.findByIdForUpdate(chatId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")

        // 도달 엔딩을 먼저 해소해 ASSISTANT 메시지 표식(reached_ending_id)으로 함께 저장한다.
        val reachedEnding = resolveReachedEnding(chat, judgment.endingName, judgmentSource)

        // 선택 기록은 아래 insert보다 **먼저** 해야 한다 — 새 ASSISTANT를 넣고 나면 그게 마지막 턴이 돼 판정이 뒤집힌다.
        recordChoiceSelection(chatId, selection, userInput)

        val lastOrder = storyMessageRepository
            .findFirstByChatIdOrderByMessageOrderDesc(chatId)
            ?.messageOrder
            ?: 0

        val user = storyMessageRepository.save(
            StoryMessage(
                chatId = chatId,
                role = MessageRole.USER,
                content = userInput,
                messageOrder = lastOrder + 1,
            ),
        )
        val assistant = storyMessageRepository.save(
            StoryMessage(
                chatId = chatId,
                role = MessageRole.ASSISTANT,
                content = aiOutput,
                messageOrder = user.messageOrder + 1,
                reachedEndingId = reachedEnding?.id,
                // 이름도 함께 박는다. FK가 없어 엔딩 행이 교체돼도 남고, **이 값이 곧 도달 턴 표식**이라
                // 상세·공유가 id 없이도 그 턴에 엔딩을 표시할 수 있다(PR #224 Codex P2 재리뷰).
                reachedEndingNameSnapshot = reachedEnding?.name,
            ),
        )

        if (choices.isNotEmpty()) {
            storyChoiceRepository.saveAll(
                choices.mapIndexed { index, text ->
                    StoryChoice(
                        chatId = chatId,
                        messageId = assistant.id,
                        choiceText = text,
                        choiceOrder = (index + 1).toShort(),
                    )
                },
            )
        }

        applyMainEventState(chat, judgment, judgmentSource)
        applyEndingReach(chat, reachedEnding)

        chat.currentTurn += 1
        storyChatRepository.save(chat)

        return PersistedTurn(turnId = assistant.id, turnNumber = chat.currentTurn, reachedEnding = reachedEnding)
    }

    /**
     * 사용자가 고른 직전 턴 선택지에 선택 결과를 기록한다(§4-3-3, KNK-819).
     *
     * 새 메시지를 insert하기 **전에**, 그리고 [persistTurn]이 잡은 채팅 락 안에서 호출해야 한다. 같은 트랜잭션에
     * 묶는 것이 설계의 하중을 받는 전제다 — 별도 트랜잭션으로 먼저 커밋하면 그 틈에 재생성이 락을 잡아
     * 방금 기록한 선택지를 [regenerateLastTurn]의 deleteAll로 지울 수 있다.
     *
     * 기록 조건(선택 정보 있음 · 지금 마지막 턴 · 순번이 그 턴의 선택지 범위 안 · 사용자가 본 세대)을 하나라도
     * 못 채우면 조용히 건너뛴다. **예외를 던지지 않아 턴 저장을 거절·롤백하지 않는다** — 관측이 채팅 전송을 막지 않는다.
     *
     * 순번은 인덱스 산술이 아니라 저장된 `choice_order` 값으로 찾는다. 0-based 실수와 순번 공백이 별도 경계
     * 분기 없이 "못 찾음 → 건너뜀" 한 갈래로 걸린다.
     *
     * **세대 가드(stale)** — 재생성은 ASSISTANT 메시지 id를 유지한 채 본문만 바꾸고 선택지를 갈아끼우므로,
     * `sourceTurnId`·`choiceOrder`가 모두 일치해도 사용자가 본 선택지가 아닐 수 있다. AI 호출 중인 이어쓰기 옆에서
     * 재생성이 커밋되고 선택지가 다시 채워지면, 뒤늦게 도는 이 메서드가 새 세대의 같은 순번 행을 선택 처리하고
     * `isEdited`를 사용자가 본 적 없는 원문과 비교해 계산한다(Codex P2). 사용자는 요청을 보내기 전에 선택지를 봤을
     * 수밖에 없으므로 정상 경로에서는 항상 `choice.createdAt <= requestedAt`이다. 요청이 시작된 뒤에 만들어진 행은
     * 본 적 없는 행이므로 기록하지 않는다. 재생성 뒤 새 선택지에서 골라 보내는 것은 **새 요청**이라 그 요청의
     * `requestedAt`이 새 행들의 `createdAt`보다 뒤여서 정상 기록된다 — 이 가드로 잃는 데이터는 없다.
     *
     * **알려진 한계 — 완전한 방어가 아니다.** 이 가드가 막는 것은 "요청이 시작된 뒤에 생긴 세대"뿐이다. 다른 탭·세션에서
     * 재생성과 선택지 재생성이 **요청 시작 전에 이미 끝난** 뒤 낡은 탭이 예전 세대 기준으로 전송하면, 새 행의
     * `createdAt`이 `requestedAt`보다 앞서 가드를 통과해 같은 순번의 새 세대 행이 기록될 수 있다(Codex 2차 지적).
     * 정확한 판정은 클라이언트가 본 선택지 세대 식별자(`choiceId`)를 요청에 실어야 가능하며 KNK-762 범위다.
     * 여기서 시각 비교를 세대 식별자로 대체하지 말 것 — 그러면 와이어 계약이 바뀐다.
     */
    private fun recordChoiceSelection(chatId: Long, selection: ChoiceSelection, userInput: String) {
        val sourceTurnId = selection.sourceTurnId ?: return // 값을 안 보낸 요청(구버전 클라이언트)은 로그도 남기지 않는다.
        val lastAssistant = storyMessageRepository
            .findFirstByChatIdAndRoleOrderByMessageOrderDesc(chatId, MessageRole.ASSISTANT)
        // 마지막 턴 대조 하나가 "다른 채팅의 턴 ID"와 "이미 진행된 낡은 턴"을 함께 막는다.
        val isLastTurn = lastAssistant?.id == sourceTurnId
        val choice = if (!isLastTurn) {
            null
        } else {
            storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(sourceTurnId)
                .firstOrNull { it.choiceOrder.toInt() == selection.choiceOrder && it.chatId == chatId }
        }
        // sourceTurnId를 보냈는데 기록하지 못한 경우만 남긴다. 0-based 순번 같은 클라이언트 실수와 세대 불일치가
        // 조용히 묻히는 것을 막는 관측이며, 사유를 구분해 싣는다. 사용자 입력·선택지 원문은 싣지 않는다.
        val skipReason = when {
            !isLastTurn -> "LAST_TURN_MISMATCH"
            choice == null -> "CHOICE_NOT_FOUND"
            choice.createdAt.isAfter(selection.requestedAt) -> "STALE_CHOICE"
            else -> null
        }
        if (skipReason != null || choice == null) {
            logger.warn(
                "채팅 선택 기록 건너뜀: reason={}, chatId={}, sourceTurnId={}, choiceOrder={}, lastAssistantId={}",
                skipReason ?: "CHOICE_NOT_FOUND",
                chatId,
                sourceTurnId,
                selection.choiceOrder,
                lastAssistant?.id,
            )
            return
        }
        // 영속 상태 엔티티라 더티 체킹으로 이 트랜잭션 커밋에 함께 반영된다(명시적 save 불필요).
        choice.isSelected = true
        choice.selectedAt = Instant.now()
        choice.isEdited = normalizeForComparison(choice.choiceText) != normalizeForComparison(userInput)
    }

    /**
     * AI가 이름으로 지목한 도달 엔딩을 해소한다.
     *
     * **후보 판정은 AI에게 보낸 목록([judgmentSource])에서 한다**(PR #224 Codex P2). 예전에는 현재
     * `story_endings`에서 이름으로 찾았는데, KNK-1065가 요청 재료를 스토리 스냅샷으로 바꾸면서 출처가 갈렸다 —
     * 비공개로 전환한 뒤 엔딩을 교체한 스토리에서는 AI가 스냅샷의 옛 이름을 돌려주고 현재 행에는 그 이름이 없어
     * 매칭이 통째로 실패했다. 사용자가 엔딩에 도달해도 기록이 남지 않는 셈이다.
     *
     * 저장은 **id를 얻을 수 있을 때만** id를 남긴다. `story_chats`·`story_messages`의 `reached_ending_id`는
     * `story_endings` FK라 없는 행을 가리키면 저장 자체가 터진다. 그래서 현재 행을 **이름으로** 다시 찾아본다 —
     * 엔딩은 시작 설정 안에서 이름이 유니크한 식별자이고(§4-3-10), 수정 API의 `endings[]` 전체 교체가 이름을
     * 유지한 채 행만 새로 만드는 것이 흔하기 때문에 id보다 이름이 잘 살아남는다.
     * 현재 행이 없으면(소유자가 이름까지 바꿨거나 지웠다) **id 없이 이름만** 남긴다 — 도달 기록은 지켜지고
     * FK도 만족한다([applyEndingReach]).
     *
     * 최소 턴 수는 백엔드가 결정적으로 판정하는 하드 조건이다(§4-3-10). 요청 후보를 거르는
     * `eligibleEndings`는 권위 있는 write-side 가드가 아니므로, AI가 문턱을 넘지 않은 이름을 앞질러 보내도
     * 여기서 재확인한다. 기준값은 **AI에게 보낸 그 목록의 min_turns**다(요청과 같은 출처).
     */
    private fun resolveReachedEnding(
        chat: StoryChat,
        endingName: String?,
        judgmentSource: TurnJudgmentSource,
    ): ReachedEnding? {
        // 최초 1회 가드. id가 비어도(행 교체로 id 없이 기록된 도달) 이름 스냅샷이 남으므로 둘 다 본다.
        if (endingName == null || chat.reachedEndingId != null || chat.reachedEndingNameSnapshot != null) {
            return null
        }
        val candidate = judgmentSource.endings.firstOrNull { it.name == endingName } ?: return null
        if (candidate.minTurns > chat.currentTurn + 1) {
            return null
        }
        val liveId = chat.startSettingId
            ?.let { storyEndingRepository.findFirstByStartSettingIdAndNameAndEnabledTrue(it, candidate.name) }
            ?.id
        return ReachedEnding(id = liveId, name = candidate.name)
    }

    /**
     * 목표 사건 상태(선정·교체·해제)와 이번 턴 완결 사건 기록을 반영한다.
     *
     * 도달 엔딩과 같은 이유로 **후보 판정은 AI에게 보낸 목록([judgmentSource])에서** 한다(PR #224 Codex P2).
     * 그 뒤 현재 행을 이름으로 찾아 id를 얻는다 — 사건도 스토리 안에서 이름이 유니크한 식별자다(KNK-523).
     *
     * **완결은 엔딩과 같은 구조로 남긴다: 라이브 행이 있으면 조인 행 + 이름, 없으면 이름만.**
     * `story_chat_main_events.main_event_id`는 NOT NULL FK라 라이브 행이 없으면 조인 행을 만들 수 없지만,
     * [StoryChat.occurredMainEventNamesSnapshot]에는 FK가 없어 이름만으로도 기록이 성립한다.
     * 정작 이 스냅샷이 필요한 경우가 **행이 사라진 뒤**라, 이름 기록을 라이브 id 분기 안에 두면 반쪽만 고친
     * 셈이 된다(PR #224 Codex P2 재리뷰).
     *
     * 목표 사건은 라이브 id가 없으면 해제된다(진행 0) — 다음 턴에 AI가 다시 지목하면 그때 다시 잡힌다.
     *
     * **목표 사건은 이름을 남기지 않는다**(의도된 선택). 완결 기록은 누적 이력이라 한 번 잃으면 되살아나지
     * 않지만, 목표 사건은 **매 턴 AI 판정으로 다시 정해진다** — 행이 사라져 해제돼도 다음 턴에 AI가 다시
     * 지목하면 그대로 복원된다. 진행 턴 수도 AI가 판정에 실어 보내는 값을 백엔드가 되돌려 싣는 것이라
     * 서버가 보존해야 할 권위값이 아니다. 잃는 것은 한 턴의 연속성뿐이라 컬럼·읽기 경로를 늘릴 값이 없다.
     */
    private fun applyMainEventState(chat: StoryChat, judgment: TurnJudgment, judgmentSource: TurnJudgmentSource) {
        // 완결 사건 기록(최초 1회 upsert). 이름 스냅샷은 이름으로, 조인 행은 (chat_id, main_event_id)
        // 유니크로 각각 중복을 막는다.
        judgment.occurredMainEventName
            // 후보 판정은 AI에게 보낸 목록이 한다(엔딩과 같은 규칙). 없는 이름은 환각·낡은 값이다.
            ?.takeIf { name -> judgmentSource.mainEvents.any { it.name == name } }
            ?.let { name ->
                // 이름 먼저 남긴다. FK가 없어 라이브 행이 없어도 쓸 수 있고, 이 스냅샷이 정작 필요한 경우가
                // 소유자가 사건을 교체·삭제해 조인 행을 만들 수 없는 바로 그 상황이다.
                val kept = chat.occurredMainEventNamesSnapshot.orEmpty()
                if (name !in kept) {
                    chat.occurredMainEventNamesSnapshot = kept + name
                }
                // 조인 행(정본)은 라이브 id가 있을 때만. main_event_id는 NOT NULL FK다.
                liveMainEventId(chat, name)?.let { eventId ->
                    if (!storyChatMainEventRepository.existsByChatIdAndMainEventId(chat.id, eventId)) {
                        storyChatMainEventRepository.save(
                            StoryChatMainEvent(chatId = chat.id, mainEventId = eventId),
                        )
                    }
                }
            }

        // 목표 사건: AI가 지목하면 그 사건·진행 턴 수로, null이면 목표 해제(진행 0).
        // 목표는 FK 컬럼 하나뿐이라 라이브 id가 없으면 남길 자리가 없다(이름을 남기지 않는 이유는 위 KDoc).
        val target = judgment.targetMainEvent
            ?.takeIf { t -> judgmentSource.mainEvents.any { it.name == t.name } }
        val targetEventId = target?.let { liveMainEventId(chat, it.name) }
        if (target != null && targetEventId != null) {
            chat.targetMainEventId = targetEventId
            chat.targetProgressTurns = target.progressTurns.coerceAtLeast(0)
        } else {
            chat.targetMainEventId = null
            chat.targetProgressTurns = 0
        }
    }

    /**
     * 사건 이름으로 **현재** 행의 id를 해소한다(FK를 만족시킬 값이 필요할 때만 쓴다).
     *
     * 후보 자격 판정과는 별개다 — 자격은 AI에게 보낸 목록이 정하고, 여기는 "지금 그 이름의 행이 있는가"만 본다.
     * 수정 API의 `mainEvents[]` 전체 교체가 이름을 유지한 채 행만 새로 만들기 때문에 id보다 이름이 잘 살아남는다.
     */
    private fun liveMainEventId(chat: StoryChat, name: String): Long? =
        storyMainEventRepository.findFirstByStoryIdAndName(chat.storyId, name)?.id

    /** 엔딩 도달 반영: 채팅 가드(reached_ending_id)·상태(ENDED)·회원 도달 집계. 게스트는 집계하지 않는다. */
    private fun applyEndingReach(chat: StoryChat, reachedEnding: ReachedEnding?) {
        if (reachedEnding == null) {
            return
        }
        // id는 현재 행을 찾았을 때만 있다(FK 제약). 못 찾아도 이름·ENDED는 남겨 도달 기록 자체는 지킨다.
        chat.reachedEndingId = reachedEnding.id
        // 도달 시점의 이름을 함께 박는다(KNK-1059). 엔딩 행이 지워지면 [StoryChat.reachedEndingId]가 FK로
        // 비워져 스토리 스냅샷의 "엔딩 id → 이름" 사전을 조회할 키가 사라지므로, 서재는 이 값으로 복구한다.
        chat.reachedEndingNameSnapshot = reachedEnding.name
        chat.status = ChatStatus.ENDED
        // 회원 도달 집계도 **id 없이 이름만으로** 남긴다(V70). 집계의 정본 식별자가 이름이라 id가 없어도
        // 기록이 성립하고, 나중에 같은 이름의 엔딩이 다시 생기면 읽기가 자연히 다시 이어진다.
        val userId = chat.userId ?: return
        // 회원 도달 집계는 독립 트랜잭션에서 기록한다. 동시 도달로 유니크 위반이 나도 그 트랜잭션만 롤백되고
        // 이 턴 저장은 유지된다. 위반은 다른 트랜잭션이 이미 같은 도달을 기록한 것이므로 멱등 결과로 흡수한다.
        try {
            endingReachRecorder.record(userId, chat.storyId, reachedEnding.id, reachedEnding.name)
        } catch (_: DataIntegrityViolationException) {
            // 동시 도달로 (회원, 스토리, 엔딩)이 이미 기록됨 — 무시.
        }
    }

    /**
     * 마지막 턴의 AI 출력과 선택지를 같은 사용자 입력으로 다시 생성한 결과로 원자적으로 교체한다(재생성, 스펙 §4-3-9).
     *
     * 교체 직전에 [expectedAssistantId]가 여전히 채팅의 마지막 턴(가장 큰 messageOrder의 ASSISTANT)인지 재확인한다.
     * 검증 시점과 저장 시점 사이에 일반 이어쓰기가 끼어들어 새 턴이 쌓였으면 마지막 턴이 바뀌므로 409로 폐기하고,
     * 호출부(ChatService)가 선차감분을 환불한다. 덮어쓰기 직전 직전 활성 출력·선택지는 [StoryMessageVersion] 이력으로
     * 보관한다(B11) — 활성본만 story_messages/story_choices에 남아 상세·SSE는 활성본만 노출한다(FE 계약 불변).
     * USER 입력·messageOrder·current_turn(turn_number)은 불변이다 — 같은 논리 턴의 AI 출력만 교체한다.
     *
     * @return 교체된 ASSISTANT 메시지 id(turnId, 제자리 교체이므로 불변)와 기존 턴 번호(turnNumber)
     */
    @Transactional
    fun regenerateLastTurn(
        chatId: Long,
        expectedAssistantId: Long,
        aiOutput: String,
        choices: List<String>,
    ): PersistedTurn {
        // 채팅 행을 비관적 쓰기 락으로 잡아 같은 채팅의 재생성을 직렬화한다(제자리 교체라 message_order 유니크로
        // 자연 직렬화되지 않음). 이 락이 없으면 동시 재생성 둘이 같은 마지막 턴 검사를 통과해 중복 과금하고
        // regenerated_count 증가가 lost update로 유실돼 대사에서 초과 환불이 재발한다(Codex P2).
        val chat = storyChatRepository.findByIdForUpdate(chatId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")

        // 재확인: 여전히 이 ASSISTANT가 마지막 턴인가. 그 사이 새 턴이 쌓였으면(마지막 id 불일치) 폐기한다.
        val lastAssistant = storyMessageRepository.findFirstByChatIdOrderByMessageOrderDesc(chatId)
        if (lastAssistant == null ||
            lastAssistant.role != MessageRole.ASSISTANT ||
            lastAssistant.id != expectedAssistantId
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "마지막 턴이 변경되어 재생성을 취소했습니다.")
        }

        // 덮어쓰기 직전에 직전 활성 출력·선택지를 버전 이력으로 보관한다(B11). append-only라 다음 순번 = 기존 개수 + 1.
        // 활성본은 story_messages/story_choices에 그대로 두므로 상세·SSE는 활성본만 노출한다(FE 계약 불변).
        val existingChoices = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(lastAssistant.id)
        storyMessageVersionRepository.save(
            StoryMessageVersion(
                messageId = lastAssistant.id,
                versionNumber = storyMessageVersionRepository.countByMessageId(lastAssistant.id).toInt() + 1,
                content = lastAssistant.content,
                choices = objectMapper.writeValueAsString(existingChoices.map { it.choiceText }),
            ),
        )

        // AI 출력 제자리 교체(활성본). USER 입력·messageOrder는 그대로 둔다.
        lastAssistant.content = aiOutput
        storyMessageRepository.save(lastAssistant)

        // 활성 선택지 전체 교체: 유니크 (message_id, choice_order) 충돌을 피하려 기존을 먼저 지우고 flush한 뒤 재삽입한다.
        if (existingChoices.isNotEmpty()) {
            storyChoiceRepository.deleteAll(existingChoices)
            storyChoiceRepository.flush()
        }
        if (choices.isNotEmpty()) {
            storyChoiceRepository.saveAll(
                choices.mapIndexed { index, text ->
                    StoryChoice(
                        chatId = chatId,
                        messageId = lastAssistant.id,
                        choiceText = text,
                        choiceOrder = (index + 1).toShort(),
                    )
                },
            )
        }

        // current_turn은 증가시키지 않는다 — 같은 논리 턴의 재생성이다. 대신 regenerated_count를 올려, 유료(CHAT_TURN)
        // 선차감이 완료된 재생성을 크레딧 대사(KNK-448)가 완료 수에 포함하게 한다(성공 재생성의 초과 환불 방지).
        chat.regeneratedCount += 1
        storyChatRepository.save(chat)
        return PersistedTurn(turnId = lastAssistant.id, turnNumber = chat.currentTurn)
    }

    /**
     * 마지막 턴의 선택지를 채운다(선택지 분리, 스펙 §4-3-3). 채팅 락으로 이어쓰기·재생성과 직렬화한다.
     *
     * [expectedAssistantId]가 여전히 마지막 턴이 아니면 409(그새 이어쓰기가 끼어듦). AI 호출은 락 밖에서 도므로,
     * 그 사이 같은 턴이 제자리 재생성돼 본문이 바뀌면([expectedAiOutput]와 현재 본문 불일치) 낡은 본문 기준 선택지를
     * 저장하지 않고 409로 폐기한다(Codex P1). 이미 선택지가 있으면 AI 결과를 버리고 저장된 값을 반환한다
     * (멱등 — 동시 호출·재진입 안전, `(message_id, choice_order)` 유니크 위반 회피).
     *
     * @return 실제 저장된 선택지와 그 턴의 turn_number(= current_turn). 반환 선택지는 story_choices에 실재하는 값이다(Codex P2).
     */
    @Transactional
    fun fillChoices(
        chatId: Long,
        expectedAssistantId: Long,
        expectedAiOutput: String,
        choices: List<String>,
    ): FilledChoices {
        val chat = storyChatRepository.findByIdForUpdate(chatId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")

        val lastAssistant = storyMessageRepository.findFirstByChatIdOrderByMessageOrderDesc(chatId)
        if (lastAssistant == null ||
            lastAssistant.role != MessageRole.ASSISTANT ||
            lastAssistant.id != expectedAssistantId
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "마지막 턴이 변경되어 선택지 생성을 취소했습니다.")
        }
        // 재생성 경합 방어: 선택지를 만든 본문과 현재 본문이 다르면(id 유지·제자리 교체) 낡은 선택지를 붙이지 않는다(Codex P1).
        if (lastAssistant.content != expectedAiOutput) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "본문이 재생성되어 선택지 생성을 취소했습니다.")
        }

        // 멱등: 이미 채워졌으면(동시 호출·재진입) 저장된 값을 그대로 반환한다.
        val existing = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(expectedAssistantId)
        if (existing.isNotEmpty()) {
            return FilledChoices(choices = existing.map { it.choiceText }, turnNumber = chat.currentTurn)
        }
        if (choices.isNotEmpty()) {
            storyChoiceRepository.saveAll(
                choices.mapIndexed { index, text ->
                    StoryChoice(
                        chatId = chatId,
                        messageId = expectedAssistantId,
                        choiceText = text,
                        choiceOrder = (index + 1).toShort(),
                    )
                },
            )
        }
        return FilledChoices(choices = choices, turnNumber = chat.currentTurn)
    }

    /** [fillChoices] 결과: 실제 저장된 선택지와 그 턴의 turn_number. */
    data class FilledChoices(val choices: List<String>, val turnNumber: Int)

    data class PersistedTurn(
        val turnId: Long,
        val turnNumber: Int,
        // 이번 턴에 도달한 엔딩(엔딩 응답이 아니면 null). SSE completed·분석 이벤트에 싣는다. 재생성은 항상 null.
        val reachedEnding: ReachedEnding? = null,
    )

    /**
     * 이번 턴에 도달한 엔딩. [id]는 현재 `story_endings` 행을 찾았을 때만 있다 — 소유자가 엔딩을 교체하고
     * 이름까지 바꾼 스토리에서는 이름만 남는다(FK를 만족시킬 id가 없다).
     */
    data class ReachedEnding(val id: Long?, val name: String)

    companion object {
        /**
         * 선택지 원문과 최종 사용자 입력을 비교하기 위한 정규화(§4-3-3, KNK-819).
         * 유니코드 NFC → 앞뒤 공백 제거 → 내부 공백 런을 스페이스 하나로 축약.
         *
         * **구두점은 지우지 않는다** — 마침표를 바꾸거나 지우는 것도 사용자가 실제로 손댄 편집이고,
         * 구두점을 뭉개면 `"간다."`와 `"간다?"`처럼 서로 다른 선택지가 같아진다.
         *
         * 같은 파일의 [StoryCreationTag.normalize](태그 키 정규화)를 재사용하지 않는 이유는 그쪽이 공백을
         * **전부 제거**하고 소문자화까지 해서, 문장 비교에 쓰면 `"문을 연다"`와 `"문을연다"`가 같아지기 때문이다.
         */
        internal fun normalizeForComparison(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFC)
                .trim()
                .replace(WHITESPACE_RUN, " ")

        // (?U)는 UNICODE_CHARACTER_CLASS. 이게 없으면 \s가 ASCII 공백만 잡아 전각 공백(U+3000)이 남는다.
        private val WHITESPACE_RUN = Regex("(?U)\\s+")
    }
}

/**
 * 사용자가 고른 직전 턴 선택지(§4-3-3, KNK-819). 프론트가 보낸 값을 그대로 담으며 서버가 보정하지 않는다.
 *
 * 두 값이 각각 nullable이라 한쪽만 온 요청도 그대로 표현된다 — [ChatTurnPersister]가 `sourceTurnId`를 기준으로
 * "값을 안 보낸 요청(무로그)"과 "보냈는데 기록 못 한 요청(WARN)"을 가른다.
 *
 * [requestedAt]은 **서버가 요청 진입 시점에 잡는 시각**이다(와이어 필드가 아니다). 선택지 세대 가드에 쓴다 —
 * 자세한 이유는 [ChatTurnPersister.recordChoiceSelection]의 세대 가드 설명 참조.
 */
data class ChoiceSelection(
    val sourceTurnId: Long? = null,
    val choiceOrder: Int? = null,
    val requestedAt: Instant = Instant.now(),
)

/** 채팅 턴 저장 트랜잭션에 반영할 AI 판정 결과(§4-3-10). 전부 선택이며, 재료가 없으면 상태 변화가 없다. */
data class TurnJudgment(
    val targetMainEvent: TargetMainEventJudgment? = null,
    val occurredMainEventName: String? = null,
    val endingName: String? = null,
)

data class TargetMainEventJudgment(val name: String, val progressTurns: Int)

/**
 * AI 턴 요청에 **실제로 실어 보낸** 엔딩 후보와 주요 사건 목록(KNK-1065, PR #224 Codex P2).
 *
 * 저장 판정이 요청과 같은 출처를 보게 하는 것이 존재 이유다. 읽을 수 있는 스토리면 현재 값을 뜬 것이고,
 * 아니면 그 스토리의 마지막 공개 버전 스냅샷이다 — 어느 쪽이든 AI가 고를 수 있었던 이름의 전부다.
 * 여기 없는 이름은 AI 환각이거나 낡은 값이므로 판정에 반영하지 않는다.
 */
data class TurnJudgmentSource(
    val endings: List<EndingSnapshot> = emptyList(),
    val mainEvents: List<MainEventSnapshot> = emptyList(),
)
