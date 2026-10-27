package com.knk.manyak.story.submission

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.repository.AdminAuditLogRepository
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.inbox.UserNotification
import com.knk.manyak.push.inbox.UserNotificationStore
import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.story.dto.UpdateStoryRequest
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.entity.StoryTakedownReason
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@SpringBootTest(properties = ["manyak.push.mode=remote"])
class StoryTakedownRemoteIntegrationTests {
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var stories: StoryRepository
    @Autowired lateinit var service: StoryTakedownService
    @Autowired lateinit var submissions: StorySubmissionRepository
    @Autowired lateinit var updates: StorySubmissionService
    @Autowired lateinit var worker: SubmissionTransactions
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var withdrawal: com.knk.manyak.user.service.UserWithdrawalService
    @Autowired lateinit var transactions: PlatformTransactionManager
    @MockitoBean lateinit var outbox: PushOutboxStore
    @MockitoBean lateinit var relay: com.knk.manyak.push.outbox.PushOutboxRelay
    @MockitoSpyBean lateinit var inbox: UserNotificationStore
    @MockitoSpyBean lateinit var audits: AdminAuditLogRepository
    private lateinit var admin: User
    private lateinit var story: Story
    private val reason = StoryTakedownRequest(StoryTakedownReason.HATE_SPEECH)
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val owner = users.save(User(nickname = "작가", servicePushEnabled = false))
        story = stories.save(Story(userId = owner.id, title = "달빛 아래의 약속"))
    }
    private fun take(request: StoryTakedownRequest = reason) = try {
        MDC.put("request_id", "takedown-test")
        MDC.put("session_id", "session-test")
        service.takeDown(admin.id, story.publicId, request)
    } finally { MDC.clear() }
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!
    @Test fun `원격은 알림 설정과 무관하게 내역과 아웃박스를 동기 기록하고 재중단은 새 메시지다`() {
        val messages = mutableListOf<PushMessage>()
        doAnswer { messages.add(it.getArgument(0)); null }.`when`(outbox).insert(anyMessage(), anyInstant())
        take()
        val first = messages.single()
        assertThat(first.kind).isEqualTo("SERVICE")
        assertThat(first.type).isEqualTo("STORY_TAKEN_DOWN")
        assertThat(first.messageId).startsWith("story-taken-down:${story.publicId}:")
        assertThat(first.requestId).isEqualTo("takedown-test")
        assertThat(first.sessionId).isEqualTo("session-test")
        assertThat(count("user_notifications")).isEqualTo(1)
        updates.update(story.publicId.toString(), UpdateStoryRequest(title = "수정한 내용"), story.userId!!)
        val row = submissions.findAll().single()
        worker.finish(row.id, row.attempt, ModerationResult("APPROVED", emptyList(), null))
        take()
        val takedowns = messages.filter { it.type == "STORY_TAKEN_DOWN" }
        assertThat(takedowns).hasSize(2)
        assertThat(takedowns[0].messageId).isNotEqualTo(takedowns[1].messageId)
    }
    @Test fun `긴 치환 제목은 내역과 푸시에 같은 축약 본문을 쓰고 저장 제목은 유지한다`() {
        story.title = "{username}".repeat(10)
        story.protagonistName = "가".repeat(30)
        stories.save(story)
        val messages = mutableListOf<PushMessage>()
        doAnswer { messages.add(it.getArgument(0)); null }.`when`(outbox).insert(anyMessage(), anyInstant())
        take(StoryTakedownRequest(StoryTakedownReason.ETC, "사".repeat(100)))
        val body = jdbc.queryForObject("SELECT body FROM user_notifications", String::class.java)!!
        assertThat(messages.single().data.getValue("body")).isEqualTo(body)
        assertThat(body).startsWith("「${"가".repeat(99)}…」")
        assertThat(body.codePointCount(0, body.length)).isLessThanOrEqualTo(300)
        assertThat(stories.findById(story.id).orElseThrow().title).isEqualTo("{username}".repeat(10))
    }
    @ParameterizedTest @ValueSource(strings = ["INBOX", "OUTBOX", "AUDIT"])
    fun `내역 아웃박스 감사 실패는 스토리와 제출본 및 내역 전체를 롤백한다`(failure: String) {
        val row = submissions.save(StorySubmission(userId = story.userId!!, storyId = story.id, kind = SubmissionKind.UPDATE, payload = "{}"))
        val exception = org.springframework.dao.TransientDataAccessResourceException("private database detail")
        when (failure) {
            "OUTBOX" -> doThrow(exception).`when`(outbox).insert(anyMessage(), anyInstant())
            "INBOX" -> doThrow(exception).`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox))
                .insert(any(UserNotification::class.java) ?: UserNotification(userId = 0, messageKey = "", type = com.knk.manyak.push.inbox.NotificationType.STORY_TAKEN_DOWN, title = "", body = ""))
            "AUDIT" -> doThrow(exception).`when`(audits).save(any(com.knk.manyak.admin.entity.AdminAuditLog::class.java)
                ?: com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id, category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
                    action = AdminAuditAction.STORY_TAKEN_DOWN, targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.STORY,
                    targetId = "", requestId = "test"))
        }
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) { take() }
        assertThat(stories.findById(story.id).orElseThrow().visibility).isEqualTo(StoryVisibility.PUBLIC)
        assertThat(stories.findById(story.id).orElseThrow().takenDownAt).isNull()
        assertThat(submissions.findById(row.id).orElseThrow().status).isEqualTo(SubmissionStatus.PENDING)
        assertThat(count("user_notifications")).isZero()
        assertThat(count("admin_audit_logs")).isZero()
    }
    @Test fun `탈퇴가 먼저 커밋되면 공개 중단은 알림을 되살리지 않는다`() {
        withdrawal.withdraw(story.userId!!)
        take()
        assertThat(stories.findById(story.id).orElseThrow().takenDownAt).isNotNull()
        assertThat(count("user_notifications")).isZero()
        verifyNoInteractions(outbox)
    }
    @Test fun `공개 중단이 소유자를 잠그면 탈퇴는 커밋을 기다린 뒤 새 내역까지 삭제한다`() {
        val recording = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val withdrawing = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        doAnswer { call ->
            recording.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            call.callRealMethod()
        }.`when`(org.springframework.test.util.AopTestUtils.getUltimateTargetObject<UserNotificationStore>(inbox))
            .insert(any(UserNotification::class.java) ?: UserNotification(userId = 0, messageKey = "",
                type = com.knk.manyak.push.inbox.NotificationType.STORY_TAKEN_DOWN, title = "", body = ""))
        try {
            val completion = executor.submit { take() }
            check(recording.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val deletion = executor.submit { withdrawing.countDown(); withdrawal.withdraw(story.userId!!) }
            check(withdrawing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            org.assertj.core.api.Assertions.assertThatThrownBy { deletion.get(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .isInstanceOf(java.util.concurrent.TimeoutException::class.java)
            release.countDown()
            completion.get(10, java.util.concurrent.TimeUnit.SECONDS)
            deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(count("user_notifications")).isZero()
            assertThat(users.findById(story.userId!!).orElseThrow().status).isEqualTo(com.knk.manyak.auth.entity.UserStatus.DELETED)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun `로그는 커밋 뒤 안전한 필드만 남기고 롤백에서는 남기지 않는다`() {
        val logger = org.slf4j.LoggerFactory.getLogger(com.knk.manyak.global.observability.StructuredLogger::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            TransactionTemplate(transactions).executeWithoutResult { tx -> take(); tx.setRollbackOnly() }
            assertThat(stories.findById(story.id).orElseThrow().takenDownAt).isNull()
            assertThat(count("user_notifications")).isZero()
            assertThat(appender.list.filter { it.formattedMessage.contains("event_name=story_taken_down") }).isEmpty()
            take()
            val logs = appender.list.filter { it.formattedMessage.contains("event_name=story_taken_down") }
            assertThat(logs).hasSize(1)
            assertThat(logs.single().formattedMessage).contains("story_public_id", "reason_code", "previous_visibility", "failed_submission_public_id", "notified")
                .doesNotContain("달빛 아래의 약속", "혐오 표현", "reason_detail", "memo")
            updates.update(story.publicId.toString(), UpdateStoryRequest(title = "고친 제목"), story.userId!!)
            val row = submissions.findAll().single()
            worker.finish(row.id, row.attempt, ModerationResult("APPROVED", emptyList(), null))
            val cleared = appender.list.filter { it.formattedMessage.contains("event_name=story_takedown_cleared") }
            assertThat(cleared).hasSize(1)
            assertThat(cleared.single().formattedMessage).contains(story.publicId.toString(), row.publicId.toString(), "visibility=PRIVATE")
        } finally { logger.detachAppender(appender); appender.stop() }
    }
    private fun anyMessage() = any(PushMessage::class.java) ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
    private fun anyInstant() = any(Instant::class.java) ?: Instant.EPOCH
}
