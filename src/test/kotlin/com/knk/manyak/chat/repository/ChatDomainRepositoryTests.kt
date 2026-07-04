package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.ChatStatus
import com.knk.manyak.chat.entity.StoryChoice
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.entity.StoryChat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest
class ChatDomainRepositoryTests {

    @Autowired
    private lateinit var storyChatRepository: StoryChatRepository

    @Autowired
    private lateinit var messageRepository: StoryMessageRepository

    @Autowired
    private lateinit var choiceRepository: StoryChoiceRepository

    private fun newSession(storyId: Long = 1L): StoryChat =
        storyChatRepository.save(StoryChat(storyId = storyId))

    @Test
    fun `세션 저장 시 기본값 ACTIVE와 current_turn 0이 적용된다`() {
        val session = newSession()

        val found = storyChatRepository.findById(session.id).orElseThrow()
        assertEquals(ChatStatus.ACTIVE, found.status)
        assertEquals(0, found.currentTurn)
        assertNull(found.deletedAt)
    }

    @Test
    fun `세션 수정 시 updatedAt이 자동으로 갱신된다`() {
        val session = newSession()
        val originalUpdatedAt = session.updatedAt

        Thread.sleep(10)
        session.title = "갱신된 제목"
        storyChatRepository.saveAndFlush(session)

        val found = storyChatRepository.findById(session.id).orElseThrow()
        assertTrue(found.updatedAt.isAfter(originalUpdatedAt))
    }

    @Test
    fun `세션 메시지를 message_order 오름차순으로 조회한다`() {
        val session = newSession()
        messageRepository.save(message(session.id, MessageRole.ASSISTANT, "둘째", order = 2))
        messageRepository.save(message(session.id, MessageRole.USER, "첫째", order = 1))

        val messages = messageRepository.findByChatIdOrderByMessageOrderAsc(session.id)

        assertEquals(listOf("첫째", "둘째"), messages.map { it.content })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), messages.map { it.role })
    }

    @Test
    fun `같은 세션에 동일 message_order는 UNIQUE 제약으로 거부된다`() {
        val session = newSession()
        messageRepository.saveAndFlush(message(session.id, MessageRole.USER, "처음", order = 1))

        assertThrows(DataIntegrityViolationException::class.java) {
            messageRepository.saveAndFlush(message(session.id, MessageRole.ASSISTANT, "중복", order = 1))
        }
    }

    @Test
    fun `최근 N개 메시지를 message_order 내림차순으로 조회한다`() {
        val session = newSession()
        (1..5).forEach { order ->
            messageRepository.save(message(session.id, MessageRole.USER, "메시지$order", order = order))
        }

        val recent = messageRepository.findByChatIdOrderByMessageOrderDesc(
            session.id,
            PageRequest.of(0, 2),
        )

        assertEquals(listOf("메시지5", "메시지4"), recent.map { it.content })
    }

    @Test
    fun `마지막 메시지 order를 조회한다`() {
        val session = newSession()
        messageRepository.save(message(session.id, MessageRole.USER, "a", order = 1))
        messageRepository.save(message(session.id, MessageRole.ASSISTANT, "b", order = 2))

        val last = messageRepository.findFirstByChatIdOrderByMessageOrderDesc(session.id)

        assertEquals(2, last?.messageOrder)
    }

    @Test
    fun `ASSISTANT 메시지에 연결된 choices를 choice_order 순으로 조회한다`() {
        val session = newSession()
        val assistant = messageRepository.save(message(session.id, MessageRole.ASSISTANT, "응답", order = 1))
        choiceRepository.save(choice(session.id, assistant.id, "둘째 선택지", order = 2))
        choiceRepository.save(choice(session.id, assistant.id, "첫째 선택지", order = 1))

        val choices = choiceRepository.findByMessageIdOrderByChoiceOrderAsc(assistant.id)

        assertEquals(listOf("첫째 선택지", "둘째 선택지"), choices.map { it.choiceText })
        assertTrue(choices.all { !it.isSelected })
    }

    @Test
    fun `동일 message_id에 동일 choice_order는 UNIQUE 제약으로 거부된다`() {
        val session = newSession()
        val assistant = messageRepository.save(message(session.id, MessageRole.ASSISTANT, "응답", order = 1))
        choiceRepository.saveAndFlush(choice(session.id, assistant.id, "선택지", order = 1))

        assertThrows(DataIntegrityViolationException::class.java) {
            choiceRepository.saveAndFlush(choice(session.id, assistant.id, "중복 선택지", order = 1))
        }
    }

    private fun message(
        chatId: Long,
        role: MessageRole,
        content: String,
        order: Int,
    ): StoryMessage =
        StoryMessage(
            chatId = chatId,
            role = role,
            content = content,
            messageOrder = order,
        )

    private fun choice(
        chatId: Long,
        messageId: Long,
        text: String,
        order: Int,
    ): StoryChoice =
        StoryChoice(
            chatId = chatId,
            messageId = messageId,
            choiceText = text,
            choiceOrder = order.toShort(),
        )
}
