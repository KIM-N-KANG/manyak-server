package com.knk.manyak.chat.service

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.global.observability.aicall.AiCallLogRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@ActiveProfiles("test")
@Import(ChatCompactTestConfig::class)
@SpringBootTest(properties = ["manyak.chat.compact.enabled=false"])
class ChatCompactDisabledMetricTests {
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var chats: StoryChatRepository
    @Autowired lateinit var messages: StoryMessageRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var persister: ChatTurnPersister
    @Autowired lateinit var calls: AiCallLogRepository
    @Autowired lateinit var queue: ChatCompactIntegrationTests.Queue
    @Autowired lateinit var ai: ChatCompactIntegrationTests.Ai
    @Autowired lateinit var tm: PlatformTransactionManager

    @BeforeEach fun reset() {
        cleaner.cleanAll()
        queue.tasks.clear(); queue.reject = false; ai.requests.clear()
    }

    private fun count() = registry.get("manyak.chat.compact.result").tag("outcome", "disabled").counter().count()

    @Order(1)
    @Test fun `기동 직후 disabled 시계열은 0으로 존재한다`() {
        assertThat(count()).isZero()
    }

    @Order(2)
    @Test fun `요약을 끄면 10번째 이어쓰기 커밋만 disabled를 늘리고 9번째와 재생성 롤백은 세지 않는다`() {
        val before = count()
        val story = stories.save(Story(title = "요약 지표", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id, currentTurn = 8))
        repeat(8) { index ->
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "입력", messageOrder = index * 2 + 1))
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "응답", messageOrder = index * 2 + 2))
        }
        fun last() = messages.findFirstByChatIdAndRoleOrderByMessageOrderDesc(chat.id, MessageRole.ASSISTANT)!!
        fun append() = persister.persistTurn(chat.id, "입력", "응답", emptyList(),
            judgmentSource = TurnJudgmentSource(), expectedState = GenerationStartState(last().id, 0), billingModelCode = "LITE")

        append()
        assertThat(count()).isEqualTo(before)
        TransactionTemplate(tm).executeWithoutResult { status -> append(); status.setRollbackOnly() }
        assertThat(count()).isEqualTo(before)
        TransactionTemplate(tm).executeWithoutResult {
            append()
            assertThat(count()).isEqualTo(before)
        }
        assertThat(count()).isEqualTo(before + 1)
        persister.regenerateLastTurn(chat.id, last().id, "재생성", emptyList(), GenerationStartState(last().id, 0), "LITE")
        assertThat(count()).isEqualTo(before + 1)
        assertThat(queue.tasks).isEmpty()
        assertThat(ai.requests).isEmpty()
        assertThat(calls.findAll()).isEmpty()
    }
}
