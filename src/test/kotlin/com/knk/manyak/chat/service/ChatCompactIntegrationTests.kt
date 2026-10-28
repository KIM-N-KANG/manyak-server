package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.*
import com.knk.manyak.chat.config.ChatCompactSettings
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.dto.BatchChatRequest
import tools.jackson.databind.json.JsonMapper
import com.knk.manyak.chat.repository.*
import com.knk.manyak.global.observability.*
import com.knk.manyak.global.observability.aicall.*
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.*

@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ChatCompactTestConfig::class)
@SpringBootTest(properties = ["manyak.chat.compact.enabled=true", "spring.datasource.url=jdbc:h2:mem:compact;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"])
class ChatCompactIntegrationTests {
    class Ai : ChatCompactAiClient {
        val requests = mutableListOf<ChatCompactAiRequest>()
        val links = mutableListOf<AiTraceLink>()
        var respond: () -> String = { "새 요약" }
        override fun compact(request: ChatCompactAiRequest, traceLink: AiTraceLink): String {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            requests += request
            links += traceLink
            return respond()
        }
    }
    class Queue : Executor {
        val tasks = ConcurrentLinkedQueue<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("test")
            tasks += command
        }
    }
    class Log : StructuredLogger() {
        val entries = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun event(eventName: String, fields: Map<String, Any?>) { entries += eventName to fields }
    }
    @Autowired lateinit var chatService: ChatService
    @Autowired lateinit var service: ChatCompactService
    @Autowired lateinit var chats: StoryChatRepository
    @Autowired lateinit var messages: StoryMessageRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var calls: AiCallLogRepository
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var persister: ChatTurnPersister
    @Autowired lateinit var ai: Ai
    @Autowired lateinit var queue: Queue
    @Autowired lateinit var log: Log
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    @Autowired lateinit var tm: PlatformTransactionManager

    @BeforeEach fun reset() {
        cleaner.cleanAll()
        ai.requests.clear(); ai.links.clear(); ai.respond = { "새 요약" }
        queue.tasks.clear(); queue.reject = false; log.entries.clear()
    }
    private fun fixture(turns: Int = 10, through: Int = 0): StoryChat {
        val story = stories.save(Story(title = "요약 테스트", genre = "판타지"))
        val chat = chats.save(StoryChat(storyId = story.id, currentTurn = turns, summary = if (through > 0) "이전 요약" else null, summaryThroughTurn = through))
        repeat(turns) { index ->
            val n = index + 1
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.USER, content = "u$n", messageOrder = n * 3))
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.SYSTEM, content = "system", messageOrder = n * 3 + 1))
            messages.save(StoryMessage(chatId = chat.id, role = MessageRole.ASSISTANT, content = "a$n <image>url</image>", messageOrder = n * 3 + 2))
        }
        return chat
    }
    private fun event(chat: StoryChat, through: Int = chat.currentTurn) = ChatTurnCommitted(chat.id, chat.publicId, chat.storyId, through)
    private fun reload(chat: StoryChat) = chats.findById(chat.id).orElseThrow()
    private fun regenerate(chat: StoryChat) {
        val current = reload(chat)
        val last = messages.findFirstByChatIdAndRoleOrderByMessageOrderDesc(chat.id, MessageRole.ASSISTANT)!!
        persister.regenerateLastTurn(chat.id, last.id, "재생성", emptyList(), GenerationStartState(last.id, current.regeneratedCount), "LITE")
    }
    private fun append(chat: StoryChat) {
        val last = messages.findFirstByChatIdAndRoleOrderByMessageOrderDesc(chat.id, MessageRole.ASSISTANT)
        persister.persistTurn(chat.id, "다음 입력", "다음 응답", emptyList(), judgmentSource = TurnJudgmentSource(),
            expectedState = GenerationStartState(last?.id, reload(chat).regeneratedCount), billingModelCode = "LITE")
    }
    private fun reason(name: String) = log.entries.last { it.first == name }.second["reason"]

    @Test fun `요약 비노출은 상세 일괄 조회 공유 응답 모두 유지한다`() {
        val chat = fixture(10)
        val other = fixture(5)
        val ids = listOf(chat.publicId.toString(), other.publicId.toString())
        val beforeOrder = chatService.getChatsByIds(BatchChatRequest(ids), null).map { it.id }
        val share = chatService.createChatShare(chat.publicId.toString(), null)
        service.compact(event(chat, 10))
        val responses = listOf(
            chatService.getChatDetail(chat.publicId.toString(), null),
            chatService.getChatsByIds(BatchChatRequest(ids), null),
            chatService.getChatShare(share.shareId, null),
        )
        responses.forEach {
            val json = JsonMapper().writeValueAsString(it)
            assertThat(json).doesNotContain("새 요약", "summaryThroughTurn", "summary_through_turn", "\"summary\"")
        }
        assertThat(chatService.getChatsByIds(BatchChatRequest(ids), null).map { it.id }).isEqualTo(beforeOrder)
    }

    @Test fun `요약 저장은 메시지와 갱신 시각을 유지하고 호출 기록과 로그를 남긴다`() {
        val chat = fixture()
        val original = messages.findByChatIdOrderByMessageOrderAsc(chat.id).map { it.id to it.content }
        service.compact(event(chat))
        val saved = reload(chat)
        assertThat(saved.summary).isEqualTo("새 요약")
        assertThat(saved.summaryThroughTurn).isEqualTo(10)
        assertThat(saved.updatedAt).isEqualTo(chat.updatedAt)
        assertThat(messages.findByChatIdOrderByMessageOrderAsc(chat.id).map { it.id to it.content }).isEqualTo(original)
        val call = calls.findAll().single()
        assertThat(call.feature).isEqualTo(AiCallFeature.CHAT_COMPACT)
        assertThat(call.status).isEqualTo(AiCallStatus.SUCCEEDED)
        assertThat(call.turnNumber).isEqualTo(10)
        assertThat(call.model).isNull(); assertThat(call.provider).isNull()
        assertThat(log.entries.any { it.first == "chat_compact_saved" }).isTrue()
    }
    @Test fun `요약 요청 구성은 첫 범위 원문과 이미지 마커를 보존하고 시스템은 제외한다`() {
        val chat = fixture()
        service.compact(event(chat))
        assertThat(ai.requests.single().summary).isEmpty()
        assertThat(ai.requests.single().history).hasSize(20)
        assertThat(ai.requests.single().history.last().content).isEqualTo("a10 <image>url</image>")
        assertThat(ai.links.single().turnNumber).isEqualTo(10)
        assertThat(ai.links.single().isRegenerated).isNull()
    }
    @Test fun `증분 요약 요청은 저장한 요약과 그 다음 턴부터 보낸다`() {
        val chat = fixture(20, 10)
        service.compact(event(chat))
        assertThat(ai.requests.single().summary).isEqualTo("이전 요약")
        assertThat(ai.requests.single().history.first().content).isEqualTo("u11")
        assertThat(ai.requests.single().history).hasSize(20)
        assertThat(reload(chat).summaryThroughTurn).isEqualTo(20)
    }
    @Test fun `요약 시작은 이어쓰기 경계 커밋 뒤 한 번이고 재생성은 시작하지 않는다`() {
        val chat = fixture(8)
        append(chat)
        assertThat(queue.tasks).isEmpty()
        TransactionTemplate(tm).executeWithoutResult {
            append(chat)
            assertThat(queue.tasks).isEmpty()
        }
        assertThat(queue.tasks).hasSize(1)
        queue.tasks.poll().run()
        regenerate(chat)
        assertThat(queue.tasks).isEmpty()
        assertThat(ai.requests).hasSize(1)
    }
    @Test fun `롤백된 턴은 요약을 시작하지 않는다`() {
        val chat = fixture(9)
        TransactionTemplate(tm).executeWithoutResult { status -> append(chat); status.setRollbackOnly() }
        assertThat(queue.tasks).isEmpty()
        assertThat(reload(chat).currentTurn).isEqualTo(9)
    }
    @Test fun `요약 범위 고정과 요약 중 이어쓰기는 서로 기다리지 않는다`() {
        val chat = fixture()
        ai.respond = { append(chat); "열 턴 요약" }
        service.compact(event(chat))
        assertThat(reload(chat).currentTurn).isEqualTo(11)
        assertThat(reload(chat).summaryThroughTurn).isEqualTo(10)
        assertThat(ai.requests.single().history).hasSize(20)
        val next = ChatHistoryContext.of(reload(chat), messages.findByChatIdOrderByMessageOrderAsc(chat.id), true, 12)
        assertThat(next.history.map { it.content }).containsExactly("다음 입력", "다음 응답")
    }
    @Test fun `진행 중 요청은 구성 시점 값을 유지하고 다음 요청부터 새 요약을 쓴다`() {
        val chat = fixture(12)
        val old = ChatHistoryContext.of(chat, messages.findByChatIdOrderByMessageOrderAsc(chat.id), true, 13)
        service.compact(event(chat, 10))
        assertThat(old.summary).isEmpty(); assertThat(old.history).hasSize(24)
        val next = ChatHistoryContext.of(reload(chat), messages.findByChatIdOrderByMessageOrderAsc(chat.id), true, 13)
        assertThat(next.summary).isEqualTo("새 요약"); assertThat(next.history).hasSize(4)
    }
    @Test fun `요약 중 삭제하면 결과를 버린다`() {
        val chat = fixture()
        ai.respond = { TransactionTemplate(tm).executeWithoutResult { chats.findByIdForUpdate(chat.id)!!.deletedAt = java.time.Instant.now() }; "삭제 뒤 응답" }
        service.compact(event(chat))
        assertThat(reload(chat).summaryThroughTurn).isZero()
        assertThat(reason("chat_compact_discarded")).isEqualTo("CHAT_DELETED")
    }
    @Test fun `늦게 끝난 이전 요약은 새 범위를 덮지 않는다`() {
        val chat = fixture(20)
        ai.respond = {
            ai.respond = { "20턴 요약" }
            service.compact(event(chat, 20))
            "10턴 요약"
        }
        service.compact(event(chat, 10))
        assertThat(reload(chat).summary).isEqualTo("20턴 요약")
        assertThat(reload(chat).summaryThroughTurn).isEqualTo(20)
        assertThat(reason("chat_compact_discarded")).isEqualTo("SUPERSEDED")
    }
    @Test fun `요약 중 재생성은 범위 안팎 모두 결과를 폐기한다`() {
        for (turns in listOf(10, 11)) {
            val chat = fixture(turns)
            ai.respond = { regenerate(chat); "오래된 요약" }
            service.compact(event(chat, 10))
            assertThat(reload(chat).summaryThroughTurn).isZero()
            assertThat(reason("chat_compact_discarded")).isEqualTo("REGENERATED")
        }
    }
    @Test fun `저장된 요약 무효화는 재생성 트랜잭션에 묶이고 범위 밖은 유지한다`() {
        val inside = fixture(10, 10)
        regenerate(inside)
        assertThat(reload(inside).summary).isNull(); assertThat(reload(inside).summaryThroughTurn).isZero()
        assertThat(log.entries.any { it.first == "chat_compact_invalidated" }).isTrue()
        val outside = fixture(12, 10)
        regenerate(outside)
        assertThat(reload(outside).summary).isEqualTo("이전 요약")
        val rollback = fixture(10, 10)
        TransactionTemplate(tm).executeWithoutResult { status -> regenerate(rollback); status.setRollbackOnly() }
        assertThat(reload(rollback).summaryThroughTurn).isEqualTo(10)
    }
    @Test fun `재생성 호출 중 요약이 먼저 저장돼도 뒤의 재생성이 비운다`() {
        val chat = fixture()
        val state = GenerationStartState(messages.findFirstByChatIdAndRoleOrderByMessageOrderDesc(chat.id, MessageRole.ASSISTANT)!!.id, 0)
        service.compact(event(chat))
        persister.regenerateLastTurn(chat.id, state.lastTurnId!!, "새 본문", emptyList(), state, "LITE")
        assertThat(reload(chat).summary).isNull()
    }
    @Test fun `실패 뒤 다음 요약과 무효화 뒤 다음 요약은 다음 경계까지 기다린다`() {
        for (invalidate in listOf(false, true)) {
            val chat = fixture(10, if (invalidate) 10 else 0)
            if (invalidate) regenerate(chat) else {
                ai.respond = { throw IllegalStateException("AI unavailable") }
                service.compact(event(chat))
            }
            repeat(9) { append(chat) }
            assertThat(queue.tasks).isEmpty()
            append(chat)
            ai.respond = { "복구 요약" }
            queue.tasks.poll().run()
            assertThat(ai.requests.last().summary).isEmpty()
            assertThat(ai.requests.last().history).hasSize(40)
        }
    }
    @Test fun `롤백 뒤 일괄 비우면 다음 요청은 원문이고 다음 경계에서 다시 요약한다`() {
        val chat = fixture(10, 10)
        jdbc.update("UPDATE story_chats SET regenerated_count = regenerated_count + 1 WHERE id = ?", chat.id)
        jdbc.update("UPDATE story_chats SET summary = NULL, summary_through_turn = 0 WHERE summary_through_turn > 0")
        val context = ChatHistoryContext.of(reload(chat), messages.findByChatIdOrderByMessageOrderAsc(chat.id), true, 11)
        assertThat(context.summary).isEmpty()
        assertThat(context.history).hasSize(20)
        repeat(9) { append(chat) }
        assertThat(queue.tasks).isEmpty()
        append(chat)
        queue.tasks.poll().run()
        assertThat(ai.requests.single().summary).isEmpty()
        assertThat(ai.requests.single().history).hasSize(40)
    }

    @Test fun `AI 오류와 공백 요약은 기존 값을 유지하고 실패 코드만 남긴다`() {
        for (response in listOf<() -> String>({ throw IllegalStateException("AI error") }, { " \n " })) {
            val chat = fixture(20, 10)
            ai.respond = response
            service.compact(event(chat))
            assertThat(reload(chat).summary).isEqualTo("이전 요약")
            assertThat(reload(chat).summaryThroughTurn).isEqualTo(10)
            assertThat(reason("chat_compact_failed")).isEqualTo("AI_FAILED")
            assertThat(calls.findAll().last().status).isEqualTo(AiCallStatus.FAILED)
            assertThat(calls.findAll().last().errorCode).isEqualTo("AI_COMPACT_FAILED")
        }
    }
    @Test fun `실행기 제출 거부는 턴 커밋을 깨지 않는다`() {
        val chat = fixture(9)
        queue.reject = true
        append(chat)
        assertThat(reload(chat).currentTurn).isEqualTo(10)
        assertThat(ai.requests).isEmpty()
        assertThat(reason("chat_compact_failed")).isEqualTo("START_FAILED")
    }
    @Test fun `요약 저장과 설정 변경은 서로의 값을 유지한다`() {
        val chat = fixture()
        ai.respond = { TransactionTemplate(tm).executeWithoutResult { chats.updateLongResponse(chat.id, true, null) }; "요약" }
        service.compact(event(chat))
        assertThat(reload(chat).longResponse).isTrue()
        assertThat(reload(chat).summary).isEqualTo("요약")
    }
}

@TestConfiguration
class ChatCompactTestConfig {
    @Bean @Primary fun compactAi() = ChatCompactIntegrationTests.Ai()
    @Bean @Primary @Qualifier("chatCompactExecutor") fun compactQueue() = ChatCompactIntegrationTests.Queue()
    @Bean @Primary fun compactLog() = ChatCompactIntegrationTests.Log()
}
