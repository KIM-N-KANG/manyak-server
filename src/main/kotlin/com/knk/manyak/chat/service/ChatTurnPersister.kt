package com.knk.manyak.chat.service

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChoiceRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
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
    private val storyPlaySessionRepository: StoryPlaySessionRepository,
    private val storyMessageRepository: StoryMessageRepository,
    private val storyChoiceRepository: StoryChoiceRepository,
) {

    /**
     * 한 턴(USER 입력 + AI 출력 + 선택지)을 원자적으로 저장하고 current_turn을 1 증가시킨다.
     * 메시지 순서는 직전 마지막 순서 n에 이어 USER=n+1, ASSISTANT=n+2로 매긴다.
     *
     * @return 저장된 ASSISTANT 메시지 id (completed 이벤트의 turnId)
     */
    @Transactional
    fun persistTurn(
        playSessionId: Long,
        userInput: String,
        aiOutput: String,
        choices: List<String>,
    ): Long {
        val session = storyPlaySessionRepository.findById(playSessionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "채팅을 찾을 수 없습니다.") }

        val lastOrder = storyMessageRepository
            .findFirstByPlaySessionIdOrderByMessageOrderDesc(playSessionId)
            ?.messageOrder
            ?: 0

        val user = storyMessageRepository.save(
            StoryMessage(
                playSessionId = playSessionId,
                role = MessageRole.USER,
                content = userInput,
                messageOrder = lastOrder + 1,
            ),
        )
        val assistant = storyMessageRepository.save(
            StoryMessage(
                playSessionId = playSessionId,
                role = MessageRole.ASSISTANT,
                content = aiOutput,
                messageOrder = user.messageOrder + 1,
            ),
        )

        if (choices.isNotEmpty()) {
            storyChoiceRepository.saveAll(
                choices.mapIndexed { index, text ->
                    StoryChoice(
                        playSessionId = playSessionId,
                        messageId = assistant.id,
                        choiceText = text,
                        choiceOrder = (index + 1).toShort(),
                    )
                },
            )
        }

        session.currentTurn += 1
        storyPlaySessionRepository.save(session)

        return assistant.id
    }
}
