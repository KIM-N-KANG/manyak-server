package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.*
import com.knk.manyak.chat.config.ChatCompactSettings
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.aicall.*
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.transaction.*
import org.springframework.transaction.support.SimpleTransactionStatus
import java.util.*
import java.util.concurrent.Executor

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
        fun service(enabled: Boolean = true, executor: Executor = Executor { it.run() }) = ChatCompactService(
            ChatCompactSettings(enabled, 10, 300), executor, chats, messages, stories, starts,
            ChatCompactAiClient { _, _ -> callCount++; "요약" },
            AiCallRecorder(calls, SimpleMeterRegistry(), "test", logger), logger, tm,
        )
        fun run() = service().compact(ChatTurnCommitted(1, chat.publicId, 2, 10))
        fun reason() = logger.entries.last { it.first == "chat_compact_failed" }.second["reason"]
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
}
