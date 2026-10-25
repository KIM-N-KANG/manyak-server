package com.knk.manyak.push

import com.google.firebase.messaging.AndroidConfig.Priority.HIGH
import com.knk.manyak.auth.entity.*
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditLot
import com.knk.manyak.credit.repository.CreditLotRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.inbox.ScheduledNotificationWriter
import com.knk.manyak.push.outbox.*
import com.knk.manyak.push.repository.*
import com.knk.manyak.push.scheduler.CreditExpiryReminderScheduler
import com.knk.manyak.push.service.*
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.*
import java.util.concurrent.*

@SpringBootTest(properties = ["manyak.push.mode=remote"])
class CreditExpiryReminderIntegrationTests {
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var lots: CreditLotRepository
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var templates: PushMessageTemplateRepository
    @Autowired private lateinit var text: PushMessageTemplateService
    @Autowired private lateinit var notifications: ScheduledNotificationWriter
    @Autowired private lateinit var writer: ScheduledPushOutboxWriter
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @MockitoBean private lateinit var store: PushOutboxStore
    @MockitoBean private lateinit var relay: PushOutboxRelay
    @MockitoBean private lateinit var sender: FcmPushSender
    private val now = Instant.parse("2026-10-25T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val messages = mutableListOf<PushMessage>()
    private val fallback = PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
    private fun service(remote: Boolean = true, cache: StringRedisTemplate = redis, at: Clock = clock) = CreditExpiryReminderService(
        lots, users, text, sender, cache, notifications, tokens, at, outbox = if (remote) writer else null)
    private fun cooldown(id: Long) = "push:credit-expiry-reminder:cooldown:$id"
    private fun count() = jdbc.queryForObject("SELECT COUNT(*) FROM credit_expiry_outbox_probe", Long::class.java)!!

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        redis.keys("push:credit-expiry-reminder:*").orEmpty().let { if (it.isNotEmpty()) redis.delete(it) }
        reset(store, sender)
        messages.clear()
        text.refresh()
        jdbc.execute("CREATE TABLE IF NOT EXISTS credit_expiry_outbox_probe(message_id VARCHAR(255) PRIMARY KEY)")
        jdbc.update("DELETE FROM credit_expiry_outbox_probe")
        doAnswer {
            val message = it.getArgument<PushMessage>(0)
            messages += message
            jdbc.update("INSERT INTO credit_expiry_outbox_probe VALUES (?) ON CONFLICT DO NOTHING", message.messageId)
        }.`when`(store).insert(any(PushMessage::class.java) ?: fallback, any(Instant::class.java) ?: now)
    }
    private fun member(status: UserStatus = UserStatus.ACTIVE, enabled: Boolean = true, token: Boolean = true): User {
        val user = users.save(User(nickname = "소멸 안내", status = status, servicePushEnabled = enabled))
        if (token) tokens.save(DevicePushToken(userId = user.id, token = "expiry-${user.id}", platform = PushPlatform.ANDROID))
        return user
    }
    private fun lot(user: User, expires: Instant? = now.plusSeconds(3600), remaining: Long = 10) =
        lots.save(CreditLot(userId = user.id, originalAmount = 10, remaining = remaining, expiresAt = expires))

    @Test fun `창 양 끝을 포함하는 세 회원의 요청은 SERVICE이고 회원별 최종 만료 시각과 금액 없는 데이터를 가진다`() {
        val first = member(); lot(first, now.plusSeconds(1)); lot(first, now.plusSeconds(100))
        lot(first, now.plusSeconds(86401)); lot(first, now.plusSeconds(80000), remaining = 0)
        tokens.save(DevicePushToken(userId = first.id, token = "extra", platform = PushPlatform.WEB))
        val second = member(); lot(second, now.plusSeconds(86400))
        val third = member(enabled = false); lot(third)
        val result = service().sendReminders()
        assertThat(result.targets).isEqualTo(3)
        assertThat(result.cooldown).isZero()
        assertThat(result.sent).isEqualTo(3)
        assertThat(count()).isEqualTo(3)
        assertThat(messages.map { it.expiresAt }).containsExactly(now.plusSeconds(100), now.plusSeconds(86400), now.plusSeconds(3600))
        messages.forEach {
            assertThat(it.kind).isEqualTo("SERVICE")
            assertThat(it.messageId).isEqualTo("credit-expiry:${it.recipientId}:2026-10-25")
            assertThat(it.type).isEqualTo("CREDIT_EXPIRY_REMINDER")
            assertThat(it.requestId).isEqualTo("unknown")
            assertThat(it.sessionId).isEqualTo("unknown")
            assertThat(it.data.keys).containsExactlyInAnyOrder("type", "title", "body", "deepLink")
            assertThat(it.data["title"]).isEqualTo("이프 소멸 예정 안내").doesNotContain("(광고)")
            assertThat(it.data["body"]).isEqualTo("곧 소멸되는 이프가 있어요")
        }
        listOf(first, second, third).forEach {
            assertThat(redis.opsForValue().get(cooldown(it.id))).isEqualTo("1")
            assertThat(redis.getExpire(cooldown(it.id))).isBetween(604790L, 604800L)
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE category='ACTIVITY' AND type='CREDIT_EXPIRY_REMINDER'", Long::class.java)).isEqualTo(3)
        val rows = jdbc.queryForList("SELECT message_key, deep_link, read_at, story_public_id, notice_public_id, notice_tag FROM user_notifications ORDER BY user_id")
        rows.zip(messages).forEach { (row, message) ->
            assertThat(row).containsEntry("message_key", message.messageId).containsEntry("deep_link", message.data["deepLink"])
                .containsEntry("read_at", null).containsEntry("story_public_id", null).containsEntry("notice_public_id", null).containsEntry("notice_tag", null)
        }
        verifyNoInteractions(sender)
    }

    @ParameterizedTest @NullSource @ValueSource(longs = [0, -1, -86400, 86401])
    fun `창 밖과 이미 만료 및 무기한 로트는 제외한다`(seconds: Long?) {
        lot(member(), seconds?.let(now::plusSeconds))
        assertThat(service().sendReminders().targets).isZero()
        assertThat(count()).isZero()
    }
    @Test fun `잔여 0 정지 탈퇴는 제외하고 서비스 알림 꺼짐과 토큰 없음도 내역과 요청을 기록한다`() {
        lot(member(), remaining = 0)
        lot(member(UserStatus.SUSPENDED)); lot(member(UserStatus.DELETED))
        val disabled = member(enabled = false); lot(disabled)
        val noToken = member(token = false); lot(noToken)
        assertThat(service().sendReminders().targets).isEqualTo(2)
        assertThat(redis.hasKey(cooldown(disabled.id))).isTrue()
        assertThat(redis.hasKey(cooldown(noToken.id))).isTrue()
        assertThat(count()).isEqualTo(2)
        // 알림함은 서비스 설정과 토큰의 영향을 받지 않는다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(2)
    }
    @Test fun `전날 쿨다운은 제외하고 만료된 쿨다운은 다시 보낸다`() {
        val user = member(); lot(user)
        redis.opsForValue().set(cooldown(user.id), "1", Duration.ofDays(6))
        val blocked = service().sendReminders()
        assertThat(blocked.targets).isEqualTo(1); assertThat(blocked.cooldown).isEqualTo(1)
        assertThat(blocked.sent).isZero()
        redis.expire(cooldown(user.id), Duration.ZERO)
        assertThat(service().sendReminders().sent).isEqualTo(1)
    }
    @Test fun `같은 날 다시 기록해도 아웃박스와 알림함은 한 건이다`() {
        val user = member(); lot(user)
        service().sendReminders()
        redis.delete(cooldown(user.id))
        assertThat(service().sendReminders().sent).isZero()
        assertThat(count()).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(1)
    }
    @Test fun `유효한 템플릿 오버라이드를 사용한다`() {
        templates.save(PushMessageTemplate(templateKey = "credit_expiry_reminder", title = "소멸 안내", body = "이프 화면에서 확인해 주세요", effectiveFrom = Instant.EPOCH))
        text.refresh(); lot(member())
        service().sendReminders()
        assertThat(messages.single().data).containsEntry("title", "소멸 안내").containsEntry("body", "이프 화면에서 확인해 주세요")
    }
    @Test fun `두 인스턴스 동시 회차 중 하나만 실행하고 24시간 선점한다`() {
        lot(member())
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val futures = (1..2).map { pool.submit { gate.await(); CreditExpiryReminderScheduler(service(), redis, mock(StructuredLogger::class.java), clock).run() } }
            gate.countDown(); futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertThat(count()).isEqualTo(1)
        assertThat(redis.getExpire("push:credit-expiry-reminder:2026-10-25")).isBetween(86390L, 86400L)
    }
    @Test fun `선점 Redis 장애는 요청 없이 선점 실패 로그를 남긴다`() {
        lot(member())
        val failed = mock(StringRedisTemplate::class.java)
        `when`(failed.opsForValue()).thenThrow(IllegalStateException("unavailable"))
        val log = mock(StructuredLogger::class.java)
        CreditExpiryReminderScheduler(service(), failed, log, clock).run()
        verify(log).event(eq("credit_expiry_reminder_claim_failed") ?: "", any<Pair<String, Any?>>() ?: ("" to null))
        assertThat(count()).isZero()
    }
    @Test fun `쿨다운 조회 장애는 아무 요청도 기록하지 않고 회차 실패를 남긴다`() {
        lot(member())
        val failed = mock(StringRedisTemplate::class.java)
        `when`(failed.hasKey(anyString())).thenThrow(IllegalStateException("unavailable"))
        val log = mock(StructuredLogger::class.java)
        CreditExpiryReminderScheduler(service(cache = failed), redis, log, clock).run()
        verify(log).event(eq("credit_expiry_reminder_failed") ?: "", any<Pair<String, Any?>>() ?: ("" to null))
        assertThat(count()).isZero()
    }
    @Test fun `기록 중 실패는 앞선 요청과 알림함을 롤백하고 쿨다운을 남기지 않는다`() {
        repeat(3) { lot(member()) }
        var attempts = 0
        doAnswer {
            jdbc.update("INSERT INTO credit_expiry_outbox_probe VALUES (?)", it.getArgument<PushMessage>(0).messageId)
            if (++attempts == 2) error("insert failed")
            1
        }.`when`(store).insert(any(PushMessage::class.java) ?: fallback, any(Instant::class.java) ?: now)
        val log = mock(StructuredLogger::class.java)
        val scheduler = CreditExpiryReminderScheduler(service(), redis, log, clock)
        scheduler.run()
        scheduler.run()
        assertThat(attempts).isEqualTo(2)
        assertThat(redis.hasKey("push:credit-expiry-reminder:2026-10-25")).isTrue()
        verify(log).event(eq("credit_expiry_reminder_failed") ?: "", any<Pair<String, Any?>>() ?: ("" to null))
        assertThat(count()).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isZero()
        assertThat(redis.keys("push:credit-expiry-reminder:cooldown:*")).isEmpty()
    }
    @Test fun `쿨다운 기록 실패는 커밋된 요청을 유지하고 회차 실패로 기록한다`() {
        lot(member())
        val failed = mock(StringRedisTemplate::class.java)
        val values = mock(org.springframework.data.redis.core.ValueOperations::class.java) as org.springframework.data.redis.core.ValueOperations<String, String>
        `when`(failed.hasKey(anyString())).thenReturn(false)
        `when`(failed.opsForValue()).thenReturn(values)
        doThrow(IllegalStateException("unavailable")).`when`(values).set(anyString(), eq("1") ?: "1", eq(Duration.ofDays(7)) ?: Duration.ofDays(7))
        val log = mock(StructuredLogger::class.java)
        CreditExpiryReminderScheduler(service(cache = failed), redis, log, clock).run()
        assertThat(count()).isEqualTo(1)
        verify(log).event(eq("credit_expiry_reminder_failed") ?: "", any<Pair<String, Any?>>() ?: ("" to null))
        assertThat(mockingDetails(log).invocations.map { it.arguments[0] }).doesNotContain("credit_expiry_reminder_sent")
        val user = users.findAll().single()
        lot(user, now.plusSeconds(90000))
        assertThat(service(at = Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC)).sendReminders().sent).isEqualTo(1)
        assertThat(count()).isEqualTo(2)
    }
    @ParameterizedTest @ValueSource(longs = [-1, 0, 1])
    fun `쿨다운은 달력 날짜가 아닌 Redis 수명 경계로 제외한다`(offset: Long) {
        val user = member(); lot(user)
        // Redis는 실제 기록 시각부터 7일을 보존한다. 경계에서 남은 수명을 직접 재현한다.
        redis.opsForValue().set(cooldown(user.id), "1", Duration.ofDays(7))
        redis.expire(cooldown(user.id), Duration.ofSeconds(-offset))
        val result = service().sendReminders()
        assertThat(result.cooldown).isEqualTo(if (offset < 0) 1 else 0)
        assertThat(result.sent).isEqualTo(if (offset < 0) 0 else 1)
    }
    @Test fun `부분 쿨다운 실패는 첫 회원 키와 커밋된 세 요청을 유지하고 다음 날 두 회원만 다시 기록한다`() {
        val members = List(3) { member().also { lot(it) } }
        val failed = mock(StringRedisTemplate::class.java, org.mockito.AdditionalAnswers.delegatesTo<Any>(redis))
        @Suppress("UNCHECKED_CAST")
        val values = mock(org.springframework.data.redis.core.ValueOperations::class.java,
            org.mockito.AdditionalAnswers.delegatesTo<Any>(redis.opsForValue())) as org.springframework.data.redis.core.ValueOperations<String, String>
        doReturn(values).`when`(failed).opsForValue()
        var writes = 0
        doAnswer {
            if (++writes == 2) error("Redis unavailable")
            redis.opsForValue().set(it.getArgument<String>(0), "1", Duration.ofDays(7))
            null
        }.`when`(values).set(anyString(), eq("1") ?: "1", eq(Duration.ofDays(7)) ?: Duration.ofDays(7))
        val log = mock(StructuredLogger::class.java)
        CreditExpiryReminderScheduler(service(cache = failed), redis, log, clock).run()
        assertThat(count()).isEqualTo(3)
        assertThat(redis.hasKey(cooldown(members[0].id))).isTrue()
        assertThat(redis.hasKey(cooldown(members[1].id))).isFalse()
        assertThat(redis.hasKey(cooldown(members[2].id))).isFalse()
        assertThat(mockingDetails(log).invocations.map { it.arguments[0] }).contains("credit_expiry_reminder_failed")
            .doesNotContain("credit_expiry_reminder_sent")
        members.forEach { lot(it, now.plusSeconds(90000)) }
        val tomorrow = Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC)
        val result = service(at = tomorrow).sendReminders()
        assertThat(result.targets).isEqualTo(3)
        assertThat(result.cooldown).isEqualTo(1)
        assertThat(result.sent).isEqualTo(2)
        assertThat(count()).isEqualTo(5)
    }
    @Test fun `로컬에서 토큰 없는 회원도 내역과 쿨다운을 받고 결과는 폐기로 남긴다`() {
        val user = member(token = false); lot(user)
        assertThat(service(remote = false).sendReminders().sent).isZero()
        assertThat(redis.hasKey(cooldown(user.id))).isTrue()
        verify(sender).recordDiscarded(eq(user.id), anyMap(), eq("credit-expiry:${user.publicId}:2026-10-25") ?: "", eq("NO_TOKENS") ?: "")
    }

    @Test fun `성공 로그는 대상 쿨다운 요청 수를 기록한다`() {
        val user = member(); lot(user)
        redis.opsForValue().set(cooldown(user.id), "1", Duration.ofDays(1))
        lot(member())
        val log = mock(StructuredLogger::class.java)
        CreditExpiryReminderScheduler(service(), redis, log, clock).run()
        verify(log).event("credit_expiry_reminder_sent", "targets" to 2, "cooldown" to 1, "sent" to 1)
    }
    @Test fun `로컬 호출 예외에도 쿨다운을 기록하고 외부 발송은 트랜잭션 밖이다`() {
        val user = member(); lot(user)
        val next = member(); lot(next)
        var calls = 0
        doAnswer {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            if (++calls == 1) error("FCM unavailable")
            null
        }.`when`(sender).sendToUser(anyLong(), anyMap(), eq(HIGH) ?: HIGH, isNull(), any(Instant::class.java), anyString())
        assertThat(service(remote = false).sendReminders().sent).isEqualTo(2)
        assertThat(calls).isEqualTo(2)
        assertThat(redis.hasKey(cooldown(user.id))).isTrue()
        assertThat(redis.hasKey(cooldown(next.id))).isTrue()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(2)
    }
    @Test fun `로컬 FCM 미설정 no-op 호출에도 쿨다운을 기록한다`() {
        val user = member(); lot(user)
        assertThat(service(remote = false).sendReminders().sent).isEqualTo(1)
        assertThat(redis.hasKey(cooldown(user.id))).isTrue()
    }

    @Test fun `로컬은 서비스 설정을 재확인하고 내역을 받은 회원 모두 쿨다운을 기록한다`() {
        val first = member(); lot(first)
        val second = member(); lot(second)
        doAnswer {
            val user = users.findById(second.id).orElseThrow(); user.servicePushEnabled = false; users.saveAndFlush(user)
            null
        }.`when`(sender).sendToUser(eq(first.id), anyMap(), eq(HIGH) ?: HIGH, isNull(), any(Instant::class.java), anyString())
        val result = service(remote = false).sendReminders()
        assertThat(result.sent).isEqualTo(1)
        verify(sender).sendToUser(eq(first.id), anyMap(), eq(HIGH) ?: HIGH, isNull(), eq(now.plusSeconds(3600)), eq("credit-expiry:${first.publicId}:2026-10-25"))
        verify(sender, never()).sendToUser(eq(second.id), anyMap(), eq(HIGH) ?: HIGH, isNull(), any(Instant::class.java), anyString())
        assertThat(redis.hasKey(cooldown(first.id))).isTrue()
        assertThat(redis.hasKey(cooldown(second.id))).isTrue()
        verify(sender).recordDiscarded(eq(second.id), anyMap(), eq("credit-expiry:${second.publicId}:2026-10-25") ?: "", eq("SERVICE_PUSH_DISABLED") ?: "")
    }
}
