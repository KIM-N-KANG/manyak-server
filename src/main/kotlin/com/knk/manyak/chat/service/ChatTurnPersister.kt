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
    ): PersistedTurn {
        // 이어쓰기(append)도 재생성과 같은 채팅 락을 잡아 두 경로를 채팅 단위로 직렬화한다. 락이 없으면
        // append가 새 메시지를 먼저 insert한 뒤 story_chats UPDATE에서 블록되는 사이, 동시 재생성이 그 미커밋
        // 행을 못 봐(READ COMMITTED) 낡은 마지막 턴을 교체·과금하고 append가 뒤이어 커밋될 수 있다(Codex P2).
        val chat = storyChatRepository.findByIdForUpdate(chatId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")

        // 도달 엔딩을 먼저 해소해 ASSISTANT 메시지 표식(reached_ending_id)으로 함께 저장한다.
        val reachedEnding = resolveReachedEnding(chat, judgment.endingName)

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

        applyMainEventState(chat, judgment)
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
     * AI가 이름으로 지목한 도달 엔딩을 채팅의 시작 설정 스코프에서 id로 해소한다.
     * 시작 설정이 없거나 이미 도달한 채팅이면 null(최초 1회 가드는 요청 단계에서 후보를 비워 이미 걸리지만 방어적으로 재확인).
     *
     * 최소 턴 수는 백엔드가 결정적으로 판정하는 하드 조건이다(§4-3-10). loadEligibleEndings가 요청 후보를 거르지만
     * 권위 있는 write-side 가드가 아니므로, AI가 아직 문턱을 넘지 않은 엔딩 이름을 잘못/앞질러 보내도 여기서 재확인해
     * 이번 턴(current_turn + 1)이 min_turns를 충족할 때만 도달로 인정한다(환각·stale로 조기 ENDED·도달 기록 방지).
     */
    private fun resolveReachedEnding(chat: StoryChat, endingName: String?): ReachedEnding? {
        val startSettingId = chat.startSettingId
        if (endingName == null || startSettingId == null || chat.reachedEndingId != null) {
            return null
        }
        val ending = storyEndingRepository
            .findFirstByStartSettingIdAndNameAndEnabledTrue(startSettingId, endingName)
            ?: return null
        if (ending.minTurns > chat.currentTurn + 1) {
            return null
        }
        return ReachedEnding(id = ending.id, name = ending.name)
    }

    /** 목표 사건 상태(선정·교체·해제)와 이번 턴 완결 사건 기록을 반영한다. 이름은 사건 id로 해소한다. */
    private fun applyMainEventState(chat: StoryChat, judgment: TurnJudgment) {
        // 완결 사건 기록(최초 1회 upsert). 같은 채팅에서 같은 사건은 유니크로 한 번만 남는다.
        judgment.occurredMainEventName?.let { name ->
            storyMainEventRepository.findFirstByStoryIdAndName(chat.storyId, name)?.let { event ->
                if (!storyChatMainEventRepository.existsByChatIdAndMainEventId(chat.id, event.id)) {
                    storyChatMainEventRepository.save(
                        StoryChatMainEvent(chatId = chat.id, mainEventId = event.id),
                    )
                }
            }
        }

        // 목표 사건: AI가 지목하면 그 사건·진행 턴 수로, null이면 목표 해제(진행 0).
        val target = judgment.targetMainEvent
        val targetEvent = target?.let { storyMainEventRepository.findFirstByStoryIdAndName(chat.storyId, it.name) }
        if (target != null && targetEvent != null) {
            chat.targetMainEventId = targetEvent.id
            chat.targetProgressTurns = target.progressTurns.coerceAtLeast(0)
        } else {
            chat.targetMainEventId = null
            chat.targetProgressTurns = 0
        }
    }

    /** 엔딩 도달 반영: 채팅 가드(reached_ending_id)·상태(ENDED)·회원 도달 집계. 게스트는 집계하지 않는다. */
    private fun applyEndingReach(chat: StoryChat, reachedEnding: ReachedEnding?) {
        if (reachedEnding == null) {
            return
        }
        chat.reachedEndingId = reachedEnding.id
        chat.status = ChatStatus.ENDED
        val userId = chat.userId ?: return
        // 회원 도달 집계는 독립 트랜잭션에서 기록한다. 동시 도달로 유니크 위반이 나도 그 트랜잭션만 롤백되고
        // 이 턴 저장은 유지된다. 위반은 다른 트랜잭션이 이미 같은 도달을 기록한 것이므로 멱등 결과로 흡수한다.
        try {
            endingReachRecorder.record(userId, chat.storyId, reachedEnding.id)
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

    data class ReachedEnding(val id: Long, val name: String)

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
