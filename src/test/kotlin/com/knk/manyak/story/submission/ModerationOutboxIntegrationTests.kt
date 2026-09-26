package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.concurrent.Executor

@ActiveProfiles("test")
@SpringBootTest(properties = ["manyak.push.mode=remote"])
class ModerationOutboxIntegrationTests {
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var submissions: StorySubmissionRepository
    @Autowired private lateinit var service: StorySubmissionService
    @Autowired private lateinit var worker: SubmissionTransactions
    @MockitoBean(name = "storyModerationExecutor") private lateinit var executor: Executor
    @MockitoBean private lateinit var relay: com.knk.manyak.push.outbox.PushOutboxRelay
    @MockitoBean private lateinit var store: PushOutboxStore
    @BeforeEach fun reset() = cleaner.cleanAll()

    @Test fun `종료와 remote 아웃박스는 한 트랜잭션이고 회차 멱등키를 사용한다`() {
        val messages = mutableListOf<PushMessage>()
        Mockito.doAnswer { call -> messages.add(call.getArgument(0)); null }.`when`(store).insert(anyMessage(), anyInstant())
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        val row = submissions.findAll().single()
        worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
        assertEquals("수정", stories.findById(story.id).orElseThrow().title)
        assertEquals("story-moderation:${row.publicId}:1", messages.single().messageId)
        assertEquals("STORY_MODERATION_COMPLETED", messages.single().type)
        assertEquals("SERVICE", messages.single().kind)
        assertEquals(user.publicId.toString(), messages.single().recipientId)
        assertEquals("APPROVED", messages.single().data["status"])
        worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
        assertEquals(1, messages.size)
    }

    @Test fun `아웃박스 DB 장애이면 라이브와 승인 상태가 함께 롤백된다`() {
        Mockito.doThrow(org.springframework.dao.TransientDataAccessResourceException("database unavailable"))
            .`when`(store).insert(anyMessage(), anyInstant())
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        val row = submissions.findAll().single()
        assertThrows(org.springframework.dao.TransientDataAccessResourceException::class.java) {
            worker.finish(row.id, 1, ModerationResult("APPROVED", emptyList(), null))
        }
        assertEquals("원본", stories.findById(story.id).orElseThrow().title)
        assertEquals(SubmissionStatus.PENDING, submissions.findById(row.id).orElseThrow().status)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = SubmissionStatus::class, names = ["REJECTED", "FAILED"])
    fun `반려와 실패도 아웃박스 장애 시 상태와 판정 필드를 롤백한다`(status: SubmissionStatus) {
        Mockito.doThrow(org.springframework.dao.TransientDataAccessResourceException("database unavailable"))
            .`when`(store).insert(anyMessage(), anyInstant())
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "원본"))
        service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정"), user.id)
        val row = submissions.findAll().single()
        fun decide() {
            if (status == SubmissionStatus.REJECTED) worker.finish(row.id, 1, ModerationResult("REJECTED", listOf(ModerationIssue("title", "TEXT", "DRUGS", "사유")), null))
            else worker.fail(row.id, 1, "MODERATION_UNAVAILABLE")
        }
        assertThrows(org.springframework.dao.TransientDataAccessResourceException::class.java) { decide() }
        val rolledBack = submissions.findById(row.id).orElseThrow()
        assertEquals(SubmissionStatus.PENDING, rolledBack.status)
        assertEquals(row.updatedAt, rolledBack.updatedAt)
        assertNull(rolledBack.decidedAt)
        assertNull(rolledBack.errorCode)
        assertTrue(rolledBack.issues.isEmpty())
        assertEquals("원본", stories.findById(story.id).orElseThrow().title)
        val messages = mutableListOf<PushMessage>()
        Mockito.doAnswer { call -> messages.add(call.getArgument(0)); null }.`when`(store).insert(anyMessage(), anyInstant())
        decide()
        assertEquals(status, submissions.findById(row.id).orElseThrow().status)
        assertEquals(status.name, messages.single().data["status"])
        assertEquals("story-moderation:${row.publicId}:1", messages.single().messageId)
    }


    @Autowired private lateinit var scheduler: SubmissionPoller
    @MockitoBean private lateinit var ai: StoryModerationClient

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `회수마다 UUID 상관 ID를 AI와 아웃박스에 전달하고 스케줄러 MDC를 복원한다`(hasContext: Boolean) {
        val previous = org.slf4j.MDC.getCopyOfContextMap()
        try {
            org.slf4j.MDC.clear()
            if (hasContext) {
                org.slf4j.MDC.put(com.knk.manyak.global.observability.MdcKeys.REQUEST_ID, "scheduler-before")
                org.slf4j.MDC.put("test_marker", "preserved")
            }
            val schedulerContext = org.slf4j.MDC.getCopyOfContextMap()
            val user = users.save(User(nickname = "작가"))
            repeat(2) {
                val story = stories.save(Story(userId = user.id, title = "원본 $it"))
                service.update(story.publicId.toString(), UpdateStoryRequest(title = "수정 $it"), user.id)
            }
            submissions.findAll().forEach {
                it.dispatchedAt = Instant.now().minusSeconds(301)
                submissions.save(it)
            }
            val queued = mutableListOf<Runnable>()
            val decorator = com.knk.manyak.global.observability.MdcTaskDecorator()
            Mockito.doAnswer { call -> queued.add(decorator.decorate(call.getArgument(0))); null }
                .`when`(executor).execute(Mockito.any(Runnable::class.java))
            val messages = mutableListOf<PushMessage>()
            Mockito.doAnswer { call -> messages.add(call.getArgument(0)); null }.`when`(store).insert(anyMessage(), anyInstant())
            okhttp3.mockwebserver.MockWebServer().use { server ->
                repeat(2) {
                    server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"decision":"APPROVED","issues":[],"error_code":null}"""))
                }
                val rest = RestStoryModerationClient(server.url("/").toString(), java.time.Duration.ofSeconds(180))
                Mockito.doAnswer { call -> rest.moderate(call.getArgument(0)) }.`when`(ai)
                    .moderate(Mockito.any(tools.jackson.databind.JsonNode::class.java) ?: tools.jackson.databind.json.JsonMapper().createObjectNode())
                scheduler.poll()
                assertEquals(schedulerContext, org.slf4j.MDC.getCopyOfContextMap())
                assertEquals(2, queued.size)
                queued.forEach { it.run() }
                assertEquals(schedulerContext, org.slf4j.MDC.getCopyOfContextMap())
                assertEquals(2, messages.size)
                assertEquals(2, messages.map { it.requestId }.toSet().size)
                messages.forEach { message ->
                    assertNotEquals("unknown", message.requestId)
                    assertEquals(message.requestId, java.util.UUID.fromString(message.requestId).toString())
                    assertEquals("unknown", message.sessionId)
                    val sent = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                    assertEquals(message.requestId, sent.getHeader(com.knk.manyak.global.observability.CorrelationHeaders.HEADER_REQUEST_ID))
                }
                assertTrue(submissions.findAll().all { it.status == SubmissionStatus.APPROVED && it.attempt == 2 })
            }
        } finally {
            if (previous == null) org.slf4j.MDC.clear() else org.slf4j.MDC.setContextMap(previous)
        }
    }

    // Mockito 매처가 반환하는 null을 Kotlin non-null 검사에 전달하지 않는다.
    private fun anyMessage(): PushMessage = Mockito.any(PushMessage::class.java) ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
}
