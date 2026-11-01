package com.knk.manyak.chat.service

import com.knk.manyak.chat.config.ChatCompactSettings
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.story.entity.Story
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.SimpleTransactionStatus
import java.util.concurrent.Executor
import com.knk.manyak.chat.client.ChatCompactAiClient
import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatRepository
import com.knk.manyak.chat.repository.StoryMessageRepository
import com.knk.manyak.global.observability.aicall.AiCallLog
import com.knk.manyak.global.observability.aicall.AiCallLogRepository
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.global.observability.aicall.AiCallStatus
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus

class ChatCompactFailureTests {
    private class Fixture {
        val chat = StoryChat(id = 1, storyId = 2, currentTurn = 10)
        val story = Story(id = 2, title = "테스트", genre = "판타지")
        val chats = mock(StoryChatRepository::class.java)
        val messages = mock(StoryMessageRepository::class.java)
        val stories = mock(StoryRepository::class.java)
        val starts = mock(StoryStartSettingRepository::class.java)
        val calls = mock(AiCallLogRepository::class.java)
        val logger = ChatCompactIntegrationTests.Log()
        val tm = object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?) = SimpleTransactionStatus()
            override fun commit(status: TransactionStatus) = Unit
            override fun rollback(status: TransactionStatus) = Unit
        }
        val registry = SimpleMeterRegistry()
        var response: () -> String = { "요약" }
        var callCount = 0
        var writes = 0
        var failWrite = 0
        init {
            `when`(chats.findById(1)).thenReturn(Optional.of(chat))
            `when`(chats.findByIdForUpdate(1)).thenReturn(chat)
            `when`(chats.updateSummary(1, "요약", 10)).thenReturn(1)
            `when`(stories.findById(2)).thenReturn(Optional.of(story))
            `when`(messages.findByChatIdOrderByMessageOrderAsc(1)).thenReturn((1..10).flatMap { n -> listOf(
                StoryMessage(chatId = 1, role = MessageRole.USER, content = "u$n", messageOrder = n * 2),
                StoryMessage(chatId = 1, role = MessageRole.ASSISTANT, content = "a$n", messageOrder = n * 2 + 1),
            ) })
            doAnswer { invocation ->
                writes++
                if (writes == failWrite) throw IllegalStateException("call log unavailable")
                invocation.arguments[0] as AiCallLog
            }.`when`(calls).save(any(AiCallLog::class.java))
        }
        fun service(enabled: Boolean = true, executor: Executor = Executor { it.run() }, interval: Int = 10) = ChatCompactService(
            ChatCompactSettings(enabled, interval, 300), executor, chats, messages, stories, starts,
            ChatCompactAiClient { _, _ -> callCount++; response() },
            AiCallRecorder(calls, registry, "test", logger), logger, registry, tm,
        )
        fun run() = service().compact(ChatTurnCommitted(1, chat.publicId, 2, 10))
        fun reason(): Any? {
            assertOutcome("failed")
            return logger.entries.last { it.first == "chat_compact_failed" }.second["reason"]
        }
        fun assertOutcome(outcome: String) {
            val counters = registry.find("manyak.chat.compact.result").counters().filter { it.count() > 0 }
            assertThat(counters).hasSize(1)
            assertThat(counters.single().id.tags).containsExactly(io.micrometer.core.instrument.Tag.of("outcome", outcome))
            assertThat(counters.single().count()).isEqualTo(1.0)
        }
    }
    @Test fun `STARTED 적재 실패면 AI를 호출하지 않는다`() {
        val f = Fixture(); f.failWrite = 1; f.run()
        assertThat(f.callCount).isZero()
        assertThat(f.reason()).isEqualTo("CALL_LOG_FAILED")
        verify(f.chats, never()).updateSummary(1, "요약", 10)
    }
    @Test fun `호출 기록 전이 실패면 요약을 저장하지 않는다`() {
        val f = Fixture(); f.failWrite = 2; f.run()
        assertThat(f.callCount).isEqualTo(1)
        assertThat(f.reason()).isEqualTo("CALL_LOG_FAILED")
        val last = mockingDetails(f.calls).invocations.filter { it.method.name == "save" }.last().arguments[0] as AiCallLog
        assertThat(last.errorCode).isNull()
        verify(f.chats, never()).updateSummary(1, "요약", 10)
    }
    @Test fun `요약 저장 DB 실패는 성공한 AI 호출 기록을 유지한다`() {
        val f = Fixture()
        `when`(f.chats.updateSummary(1, "요약", 10)).thenThrow(IllegalStateException("save unavailable"))
        f.run()
        assertThat(f.reason()).isEqualTo("SAVE_FAILED")
        assertThat(f.writes).isEqualTo(2)
        val saved = mockingDetails(f.calls).invocations.filter { it.method.name == "save" }.last().arguments[0] as AiCallLog
        assertThat(saved.status).isEqualTo(AiCallStatus.SUCCEEDED)
    }
    @Test fun `범위 읽기 DB 실패는 AI 호출 없이 시작 실패로 끝난다`() {
        val f = Fixture()
        `when`(f.messages.findByChatIdOrderByMessageOrderAsc(1)).thenThrow(IllegalStateException("read unavailable"))
        f.run()
        assertThat(f.callCount).isZero()
        assertThat(f.reason()).isEqualTo("START_FAILED")
    }
    @Test fun `요약을 끄면 경계 턴도 제출하지 않는다`() {
        val f = Fixture()
        f.service(false).afterCommit(ChatTurnCommitted(1, f.chat.publicId, 2, 10))
        assertThat(f.callCount).isZero()
        assertThat(f.logger.entries).isEmpty()
    }
    @Test fun `요약 저장은 saved를 한 번 집계한다`() {
        val f = Fixture(); f.run()
        f.assertOutcome("saved")
    }
    @Test fun `모든 요약 폐기 사유는 discarded를 한 번 집계한다`() {
        for (reason in listOf("CHAT_DELETED", "REGENERATED", "SUPERSEDED")) {
            val f = Fixture()
            f.response = {
                when (reason) {
                    "CHAT_DELETED" -> f.chat.deletedAt = java.time.Instant.now()
                    "REGENERATED" -> f.chat.regeneratedCount++
                    "SUPERSEDED" -> f.chat.summaryThroughTurn = 10
                }
                "요약"
            }
            f.run()
            f.assertOutcome("discarded")
            assertThat(f.logger.entries.single { it.first == "chat_compact_discarded" }.second["reason"]).isEqualTo(reason)
        }
    }
    @Test fun `AI 오류와 공백 요약은 failed를 한 번 집계한다`() {
        for (blank in listOf(false, true)) {
            val f = Fixture()
            f.response = { if (blank) " " else throw IllegalStateException("AI unavailable") }
            f.run()
            assertThat(f.reason()).isEqualTo("AI_FAILED")
        }
    }
    @Test fun `실행기 거부는 failed를 한 번 집계한다`() {
        val f = Fixture()
        f.service(executor = Executor { throw java.util.concurrent.RejectedExecutionException("test") })
            .afterCommit(ChatTurnCommitted(1, f.chat.publicId, 2, 10))
        assertThat(f.reason()).isEqualTo("START_FAILED")
        assertThat(f.callCount).isZero()
    }
    @Test fun `요약을 끄면 설정한 간격의 배수만 disabled로 집계한다`() {
        val f = Fixture()
        val service = f.service(enabled = false, interval = 3)
        for (through in 1..7) service.afterCommit(ChatTurnCommitted(1, f.chat.publicId, 2, through))
        assertThat(f.registry.get("manyak.chat.compact.result").tag("outcome", "disabled").counter().count()).isEqualTo(2.0)
        assertThat(f.callCount).isZero()
        assertThat(f.writes).isZero()
    }
}
