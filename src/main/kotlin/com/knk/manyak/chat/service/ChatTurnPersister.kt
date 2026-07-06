package com.knk.manyak.chat.service

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryChatRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

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
) {

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
    ): PersistedTurn {
        // 이어쓰기(append)도 재생성과 같은 채팅 락을 잡아 두 경로를 채팅 단위로 직렬화한다. 락이 없으면
        // append가 새 메시지를 먼저 insert한 뒤 story_chats UPDATE에서 블록되는 사이, 동시 재생성이 그 미커밋
        // 행을 못 봐(READ COMMITTED) 낡은 마지막 턴을 교체·과금하고 append가 뒤이어 커밋될 수 있다(Codex P2).
        val chat = storyChatRepository.findByIdForUpdate(chatId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.")

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

        chat.currentTurn += 1
        storyChatRepository.save(chat)

        return PersistedTurn(turnId = assistant.id, turnNumber = chat.currentTurn)
    }

    /**
     * 마지막 턴의 AI 출력과 선택지를 같은 사용자 입력으로 다시 생성한 결과로 원자적으로 교체한다(재생성, 스펙 §4-3-9).
     *
     * 교체 직전에 [expectedAssistantId]가 여전히 채팅의 마지막 턴(가장 큰 messageOrder의 ASSISTANT)인지 재확인한다.
     * 검증 시점과 저장 시점 사이에 일반 이어쓰기가 끼어들어 새 턴이 쌓였으면 마지막 턴이 바뀌므로 409로 폐기하고,
     * 호출부(ChatService)가 선차감분을 환불한다. 버전 이력은 두지 않으므로 이전 본문·선택지는 보관하지 않고 덮어쓴다.
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

        // AI 출력 제자리 교체(이전 본문 미보관). USER 입력·messageOrder는 그대로 둔다.
        lastAssistant.content = aiOutput
        storyMessageRepository.save(lastAssistant)

        // 선택지 전체 교체: 유니크 (message_id, choice_order) 충돌을 피하려 기존을 먼저 지우고 flush한 뒤 재삽입한다.
        val existingChoices = storyChoiceRepository.findByMessageIdOrderByChoiceOrderAsc(lastAssistant.id)
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

    data class PersistedTurn(val turnId: Long, val turnNumber: Int)
}
