package com.knk.manyak.story.submission

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/** MODERATION 5-5-6·10, STORY 5-5-5, ADMIN 5-5-6. */
@RecordApplicationEvents
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.official-user-public-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"])
class StoryTakedownIntegrationTests {
    @Autowired lateinit var client: RestTestClient
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var submissions: StorySubmissionRepository
    @Autowired lateinit var service: StorySubmissionService
    @Autowired lateinit var takedowns: StoryTakedownService
    @Autowired lateinit var worker: SubmissionTransactions
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var events: ApplicationEvents
    private lateinit var owner: User
    private lateinit var token: String
    private lateinit var admin: User
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
        owner = users.save(User(nickname = "작가", servicePushEnabled = false))
    }
    private fun story(visibility: StoryVisibility = StoryVisibility.PUBLIC) =
        stories.save(Story(userId = owner.id, title = "{username}의 약속", protagonistName = "서린", visibility = visibility))
    private fun take(id: Any, body: String = """{"reasonCode":"COPYRIGHT_INFRINGEMENT","memo":"내부 전용"}""", auth: String? = token) =
        client.post().uri("/api/v1/admin/moderation/stories/$id/takedown")
            .also { spec -> auth?.let { spec.header("Authorization", "Bearer $it") } }
            .header("Content-Type", "application/json").body(body).exchange()
    private fun json(response: RestTestClient.ResponseSpec): JsonNode =
        mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!

    @Test fun `인증 없음과 앱 토큰과 역할 없는 관리자 토큰을 거부한다`() {
        val s = story()
        take(s.publicId, auth = null).expectStatus().isUnauthorized
        take(s.publicId, auth = jwt.issueAccessToken(owner.publicId)).expectStatus().isUnauthorized
        take(s.publicId, auth = jwt.issueAdminToken(owner.publicId)).expectStatus().isForbidden
        assertThat(count("admin_audit_logs")).isZero()
    }
    @Test fun `공개 중단 JSON 파싱 실패 DEBUG 로그는 사유와 메모 원문을 남기지 않는다`() {
        val uri = "/api/v1/admin/moderation/stories/${story().publicId}/takedown"
        val logger = org.slf4j.LoggerFactory.getLogger(com.knk.manyak.global.error.GlobalExceptionHandler::class.java) as ch.qos.logback.classic.Logger
        val previous = logger.level
        val appender = object : ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            override fun append(event: ch.qos.logback.classic.spi.ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }.apply { start() }
        logger.level = ch.qos.logback.classic.Level.DEBUG
        logger.addAppender(appender)
        try {
            val detail = "PRIVATE_DETAIL_MARKER"
            val memo = "PRIVATE_MEMO_MARKER"
            for (body in listOf(
                """{"reasonCode":"ETC","reasonDetail":$detail,"memo":"$memo"}""",
                """{"reasonCode":"ETC","reasonDetail":"$detail","memo":$memo}""",
            )) {
                appender.list.clear()
                client.post().uri(uri).header("Authorization", "Bearer $token")
                    .header("X-Manyak-Request-Id", "req-takedown-json").header("Content-Type", "application/json")
                    .body(body).exchange().expectStatus().isBadRequest.expectBody().jsonPath("$.details").isEmpty()
                val log = appender.list.single()
                assertThat(log.level).isEqualTo(ch.qos.logback.classic.Level.DEBUG)
                assertThat(log.formattedMessage).isEqualTo(
                    "Malformed request body: path=$uri, error_type=HttpMessageNotReadableException, request_id=req-takedown-json")
                assertThat(log.message).doesNotContain(detail, memo)
                assertThat(log.argumentArray.map { it.toString() }.joinToString()).doesNotContain(detail, memo)
                assertThat(log.argumentArray.orEmpty().toList()).noneMatch { it is Throwable }
                assertThat(log.throwableProxy).isNull()
            }
        } finally { logger.detachAppender(appender); logger.level = previous; appender.stop() }
    }
    @Test fun `공개 스토리의 스냅샷과 감사 및 알림 내역을 함께 기록한다`() {
        val s = story()
        val body = json(take(s.publicId).expectStatus().isOk)
        assertThat(body["storyId"].asText()).isEqualTo(s.publicId.toString())
        assertThat(body["visibility"].asText()).isEqualTo("PRIVATE")
        assertThat(body["takedownReason"].asText()).isEqualTo("COPYRIGHT_INFRINGEMENT")
        assertThat(body["takedownReasonDetail"].isNull).isTrue()
        assertThat(body["takenDownAt"].asText()).isNotBlank()
        assertThat(body.toString()).doesNotContain("memo", "내부 전용")
        assertThat(stories.findById(s.id).orElseThrow().visibility).isEqualTo(StoryVisibility.PRIVATE)
        assertThat(count("story_public_snapshots")).isEqualTo(1)
        val audit = jdbc.queryForMap("SELECT * FROM admin_audit_logs")
        assertThat(audit["action"]).isEqualTo("STORY_TAKEN_DOWN")
        assertThat(audit["reason"]).isEqualTo("저작권 침해")
        assertThat(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs", String::class.java)).contains("PUBLIC")
        assertThat(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs", String::class.java)).contains("PRIVATE", "내부 전용")
        val inbox = jdbc.queryForMap("SELECT * FROM user_notifications")
        assertThat(inbox["type"]).isEqualTo("STORY_TAKEN_DOWN")
        assertThat(inbox["category"]).isEqualTo("ACTIVITY")
        assertThat(inbox["title"]).isEqualTo("스토리 공개가 제한됐어요")
        assertThat(inbox["body"].toString()).contains("서린의 약속", "저작권 침해").doesNotContain("내부 전용")
        assertThat(inbox["deep_link"].toString()).endsWith("/studio?utm_source=inbox&utm_medium=inbox&utm_campaign=story_taken_down&utm_content=story_taken_down")
    }
    @Test fun `토큰 치환으로 제목이 길어져도 허용된 스토리는 공개 중단할 수 있어야 한다`() {
        val s = stories.save(Story(userId = owner.id, title = "{username}".repeat(10), protagonistName = "가".repeat(30)))
        take(s.publicId, mapper.writeValueAsString(mapOf("reasonCode" to "ETC", "reasonDetail" to "사".repeat(100))))
            .expectStatus().isOk
        val body = jdbc.queryForObject("SELECT body FROM user_notifications", String::class.java)!!
        assertThat(body).startsWith("「${"가".repeat(99)}…」")
        assertThat(body.codePointCount(0, body.length)).isLessThanOrEqualTo(300)
        assertThat(stories.findById(s.id).orElseThrow().title).isEqualTo("{username}".repeat(10))
    }
    @Test fun `비공개 스토리도 중단하고 메모 생략은 감사에서 null이다`() {
        val s = story(StoryVisibility.PRIVATE)
        take(s.publicId, """{"reasonCode":"SEXUAL_CONTENT","reasonDetail":null}""").expectStatus().isOk
        val after = mapper.readTree(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs", String::class.java)!!)
        assertThat(after["memo"].isNull).isTrue()
        assertThat(count("user_notifications")).isEqualTo(1)
    }
    @Test fun `기타 사유를 저장하고 감사 사유와 알림 본문에 쓴다`() {
        val s = story()
        val result = json(take(s.publicId, """{"reasonCode":"ETC","reasonDetail":"실존 인물 비방"}""").expectStatus().isOk)
        assertThat(result["takedownReasonDetail"].asText()).isEqualTo("실존 인물 비방")
        assertThat(jdbc.queryForObject("SELECT takedown_reason_detail FROM stories WHERE id=?", String::class.java, s.id)).isEqualTo("실존 인물 비방")
        assertThat(jdbc.queryForObject("SELECT reason FROM admin_audit_logs", String::class.java)).isEqualTo("실존 인물 비방")
        assertThat(jdbc.queryForObject("SELECT body FROM user_notifications", String::class.java)).contains("실존 인물 비방 사유로").doesNotContain("기타")
    }
    @ParameterizedTest @ValueSource(strings = ["{}", "{\"reasonCode\":\"SPAM\"}",
        "{\"reasonCode\":\"ETC\"}", "{\"reasonCode\":\"ETC\",\"reasonDetail\":\"  \"}",
        "{\"reasonCode\":\"SEXUAL_CONTENT\",\"reasonDetail\":\"사유\"}",
        "{\"reasonCode\":\"SEXUAL_CONTENT\",\"memo\":\"  \"}"])
    fun `잘못된 사유와 메모는 400이다`(body: String) {
        take(story().publicId, body).expectStatus().isBadRequest
        assertThat(count("admin_audit_logs")).isZero()
    }
    @Test fun `입력 사유 101자와 메모 501자는 거부한다`() {
        val s = story()
        take(s.publicId, mapper.writeValueAsString(mapOf("reasonCode" to "ETC", "reasonDetail" to "가".repeat(101)))).expectStatus().isBadRequest
        take(s.publicId, mapper.writeValueAsString(mapOf("reasonCode" to "HATE_SPEECH", "memo" to "가".repeat(501)))).expectStatus().isBadRequest
    }
    @Test fun `대상이 아니면 409이고 없는 식별자와 형식 오류는 구분한다`() {
        val s = story()
        take(s.publicId).expectStatus().isOk
        take(s.publicId).expectStatus().isEqualTo(409)
        val deleted = stories.save(Story(userId = owner.id, title = "삭제", deletedAt = Instant.now()))
        val guest = stories.save(Story(title = "게스트"))
        val official = users.save(User(publicId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), nickname = "공식"))
        val original = stories.save(Story(userId = official.id, title = "공식"))
        listOf(deleted, guest, original).forEach { take(it.publicId).expectStatus().isEqualTo(409) }
        take(UUID.randomUUID()).expectStatus().isNotFound
        take("invalid").expectStatus().isBadRequest
        assertThat(count("admin_audit_logs")).isEqualTo(1)
        assertThat(count("user_notifications")).isEqualTo(1)
    }
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `대기와 보류 제출본을 실패로 끝내고 늦은 승인은 버린다`(held: Boolean) {
        val s = story()
        val row = submissions.save(StorySubmission(userId = owner.id, storyId = s.id, kind = SubmissionKind.UPDATE,
            payload = "{}", inputForm = "{}", attempt = 4, retryCount = 2, dispatchedAt = Instant.now(),
            heldAt = if (held) Instant.now() else null, holdReason = if (held) "MODEL_CALL_FAILED" else null,
            nextAttemptAt = if (held) null else Instant.now()))
        take(s.publicId).expectStatus().isOk
        val ended = submissions.findById(row.id).orElseThrow()
        assertThat(ended.status).isEqualTo(SubmissionStatus.FAILED)
        assertThat(ended.errorCode).isEqualTo("STORY_TAKEN_DOWN")
        assertThat(ended.attempt).isEqualTo(4)
        assertThat(ended.retryCount).isZero()
        assertThat(listOf(ended.heldAt, ended.holdReason, ended.nextAttemptAt, ended.dispatchedAt)).containsOnlyNulls()
        assertThat(ended.decidedAt).isNotNull()
        assertThat(ended.inputForm).isEqualTo("{}")
        worker.finish(row.id, 4, ModerationResult("APPROVED", emptyList(), null))
        assertThat(stories.findById(s.id).orElseThrow().visibility).isEqualTo(StoryVisibility.PRIVATE)
        assertThat(events.stream(StoryModerationCompleted::class.java).count()).isZero()
    }
    @Test fun `반려된 수정은 그대로 보존한다`() {
        val s = story()
        val row = submissions.save(StorySubmission(userId = owner.id, storyId = s.id, kind = SubmissionKind.UPDATE,
            payload = "{}", status = SubmissionStatus.REJECTED))
        take(s.publicId).expectStatus().isOk
        assertThat(submissions.findById(row.id).orElseThrow().status).isEqualTo(SubmissionStatus.REJECTED)
    }
    @ParameterizedTest @ValueSource(strings = ["PUBLIC", "CONTENT", "REJECTED"])
    fun `중단 뒤 수정은 승인으로만 표시를 지우고 요청한 공개 범위를 적용한다`(scenario: String) {
        val s = story()
        take(s.publicId).expectStatus().isOk
        val request = if (scenario == "CONTENT") UpdateStoryRequest(title = "고친 제목") else UpdateStoryRequest(visibility = StoryVisibility.PUBLIC)
        val accepted = service.update(s.publicId.toString(), request, owner.id)
        assertThat(accepted).isInstanceOf(SubmissionAccepted::class.java)
        assertThat(stories.findById(s.id).orElseThrow().visibility).isEqualTo(StoryVisibility.PRIVATE)
        val row = submissions.findAll().single()
        worker.finish(row.id, row.attempt, ModerationResult(if (scenario == "REJECTED") "REJECTED" else "APPROVED", emptyList(), null))
        val live = jdbc.queryForMap("SELECT * FROM stories WHERE id=?", s.id)
        if (scenario == "REJECTED") {
            assertThat(live["taken_down_at"]).isNotNull()
            assertThat(live["visibility"]).isEqualTo("PRIVATE")
        } else {
            assertThat(listOf(live["taken_down_at"], live["takedown_reason"], live["takedown_reason_detail"])).containsOnlyNulls()
            assertThat(live["visibility"]).isEqualTo(if (scenario == "PUBLIC") "PUBLIC" else "PRIVATE")
        }
    }
    @Test fun `공개 중단 스토리의 HTTP 공개 전환은 202 접수로 응답한다`() {
        val s = story()
        take(s.publicId).expectStatus().isOk
        val response = json(client.patch().uri("/api/v1/stories/${s.publicId}")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(owner.publicId)}")
            .header("Content-Type", "application/json").body("""{"visibility":"PUBLIC"}""").exchange().expectStatus().isAccepted)
        assertThat(response["status"].asText()).isEqualTo("PENDING")
        assertThat(response["submissionId"].asText()).isNotBlank()
        assertThat(stories.findById(s.id).orElseThrow().visibility).isEqualTo(StoryVisibility.PRIVATE)
    }
    @Test fun `중단 상태의 비공개 단독 변경은 200이며 표시를 유지한다`() {
        val s = story()
        take(s.publicId).expectStatus().isOk
        assertThat(service.update(s.publicId.toString(), UpdateStoryRequest(visibility = StoryVisibility.PRIVATE), owner.id)).isNotInstanceOf(SubmissionAccepted::class.java)
        assertThat(count("story_submissions")).isZero()
        assertThat(jdbc.queryForMap("SELECT * FROM stories WHERE id=?", s.id)["taken_down_at"]).isNotNull()
    }
    @ParameterizedTest @ValueSource(strings = ["DELETED", "SUSPENDED"])
    fun `탈퇴 소유자는 알림이 없고 정지 소유자는 내역을 남긴다`(status: String) {
        owner.status = UserStatus.valueOf(status); users.save(owner)
        take(story().publicId).expectStatus().isOk
        assertThat(count("user_notifications")).isEqualTo(if (status == "DELETED") 0 else 1)
    }
    @Test fun `동시 공개 중단은 하나만 성공하고 감사와 내역이 한 건이다`() {
        val s = story()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val gate = java.util.concurrent.CyclicBarrier(2)
        try {
            val results = (1..2).map { executor.submit<Int> {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                org.slf4j.MDC.put("request_id", "concurrent-takedown")
                try { takedowns.takeDown(admin.id, s.publicId, StoryTakedownRequest(com.knk.manyak.story.entity.StoryTakedownReason.HATE_SPEECH)); 200 }
                catch (ex: org.springframework.web.server.ResponseStatusException) { ex.statusCode.value() }
                finally { org.slf4j.MDC.clear() }
            } }.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(results).containsExactlyInAnyOrder(200, 409)
            assertThat(count("admin_audit_logs")).isEqualTo(1)
            assertThat(count("user_notifications")).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }
    @Test fun `소유자의 공개 변경과 겹쳐도 최종 상태는 비공개 중단이다`() {
        val s = story(StoryVisibility.PRIVATE)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val gate = java.util.concurrent.CyclicBarrier(2)
        try {
            val a = executor.submit {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                org.slf4j.MDC.put("request_id", "race-takedown")
                try { takedowns.takeDown(admin.id, s.publicId, StoryTakedownRequest(com.knk.manyak.story.entity.StoryTakedownReason.HATE_SPEECH)) }
                finally { org.slf4j.MDC.clear() }
            }
            val b = executor.submit {
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                service.update(s.publicId.toString(), UpdateStoryRequest(visibility = StoryVisibility.PUBLIC), owner.id)
            }
            a.get(15, java.util.concurrent.TimeUnit.SECONDS); b.get(15, java.util.concurrent.TimeUnit.SECONDS)
            val live = stories.findById(s.id).orElseThrow()
            assertThat(live.visibility).isEqualTo(StoryVisibility.PRIVATE)
            assertThat(live.takenDownAt).isNotNull()
        } finally { executor.shutdownNow() }
    }
    @Test fun `관리자 목록과 상세는 공개 중단 필드를 항상 포함한다`() {
        val stopped = story()
        val untouched = story()
        take(stopped.publicId, """{"reasonCode":"ETC","reasonDetail":"실존 인물 비방"}""").expectStatus().isOk
        for (s in listOf(stopped, untouched)) {
            val b = json(client.get().uri("/api/v1/admin/stories/${s.publicId}").header("Authorization", "Bearer $token").exchange().expectStatus().isOk)
            assertThat(b.has("takenDownAt") && b.has("takedownReason") && b.has("takedownReasonDetail")).isTrue()
            if (s.id == stopped.id) assertThat(b["takedownReasonDetail"].asText()).isEqualTo("실존 인물 비방")
            else assertThat(b["takedownReasonDetail"].isNull).isTrue()
        }
        val list = json(client.get().uri("/api/v1/admin/stories").header("Authorization", "Bearer $token").exchange().expectStatus().isOk)
        assertThat(list["items"].toList().all { it.has("takenDownAt") && it.has("takedownReason") && it.has("takedownReasonDetail") }).isTrue()
    }
}
