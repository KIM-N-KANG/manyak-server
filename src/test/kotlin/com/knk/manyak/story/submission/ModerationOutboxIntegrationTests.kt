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

@org.springframework.test.context.event.RecordApplicationEvents
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

    @Autowired private lateinit var events: org.springframework.test.context.event.ApplicationEvents
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
        "CREATE, APPROVED", "CREATE, REJECTED", "CREATE, FAILED",
        "UPDATE, APPROVED", "UPDATE, REJECTED", "UPDATE, FAILED",
    )
    fun `새 등록과 수정의 종료 이벤트와 remote 메시지는 제출본 제목을 사용한다`(kind: SubmissionKind, status: SubmissionStatus) {
        val messages = mutableListOf<PushMessage>()
        Mockito.doAnswer { call -> messages.add(call.getArgument(0)); null }.`when`(store).insert(anyMessage(), anyInstant())
        val user = users.save(User(nickname = "작가"))
        if (kind == SubmissionKind.CREATE) {
            service.create(mapper.readValue("""{
                "title":"제출본 제목", "oneLineIntro":"소개", "genres":["판타지"],
                "storySettings":{"worldSetting":"세계", "characterSetting":"인물", "userRoleSetting":"역할", "ruleSetting":"규칙"},
                "startSettings":[{"name":"시작", "prologue":"도입", "startSituation":"상황", "suggestedInputs":["하나","둘","셋"]}]
            }""", com.knk.manyak.story.dto.CreateGeneralStoryRequest::class.java), user.id)
        } else {
            val story = stories.save(Story(userId = user.id, title = "라이브 제목"))
            service.update(story.publicId.toString(), UpdateStoryRequest(title = "제출본 제목"), user.id)
        }
        val row = submissions.findAll().single()
        if (status == SubmissionStatus.FAILED) worker.fail(row.id, 1, "APPLY_FAILED")
        else worker.finish(row.id, 1, ModerationResult(status.name, emptyList(), null))

        val event = events.stream(StoryModerationCompleted::class.java).toList().single()
        assertEquals("제출본 제목", event.title)
        assertEquals(status, event.status)
        val (title, body) = when (status) {
            SubmissionStatus.APPROVED -> "검수를 통과했어요" to "「제출본 제목」이 등록됐어요. 지금 확인해 보세요."
            SubmissionStatus.REJECTED -> "검수에서 반려됐어요" to "「제출본 제목」은 등록되지 않았어요. 내용을 수정해 다시 제출해 주세요."
            SubmissionStatus.FAILED -> "검수를 진행하지 못했어요" to "「제출본 제목」 검수 중 문제가 생겼어요. 잠시 후 다시 제출해 주세요."
            SubmissionStatus.PENDING -> error("종료 상태만 검증한다")
        }
        val data = messages.single().data
        assertEquals(title, data["title"])
        assertEquals(body, data["body"])
        assertEquals("https://manyak.app/studio", data["deepLink"])
        assertEquals(row.publicId.toString(), data["submissionId"])
        assertEquals(status.name, data["status"])
        if (kind == SubmissionKind.CREATE && status != SubmissionStatus.APPROVED) assertFalse(data.containsKey("storyId"))
        else assertEquals(stories.findAll().single().publicId.toString(), data["storyId"])
    }

    @Test fun `제목을 생략한 수정도 제출 당시 폼의 제목을 이벤트에 싣는다`() {
        val user = users.save(User(nickname = "작가"))
        val story = stories.save(Story(userId = user.id, title = "제출 당시 제목"))
        service.update(story.publicId.toString(), UpdateStoryRequest(oneLineIntro = "소개 수정"), user.id)
        val row = submissions.findAll().single()
        story.title = "이후 라이브 제목"
        stories.save(story)
        worker.finish(row.id, 1, ModerationResult("REJECTED", emptyList(), null))
        val event = events.stream(StoryModerationCompleted::class.java).toList().single()
        assertEquals("제출 당시 제목", event.title)
    }

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
            else worker.fail(row.id, 1, "APPLY_FAILED")
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
