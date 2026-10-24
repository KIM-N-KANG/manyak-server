package com.knk.manyak.push.inbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.outbox.*
import com.knk.manyak.push.repository.*
import com.knk.manyak.push.scheduler.AttendanceReminderScheduler
import com.knk.manyak.push.service.*
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

/** 실제 INSERT 뒤 실패시켜 앞선 내역과 아웃박스까지 회차 단위로 롤백되는지 검증한다. */
@SpringBootTest(properties = ["manyak.push.mode=remote"])
class ScheduledNotificationAtomicityTests {
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var promotion: PromotionPushService
    @Autowired private lateinit var attendance: AttendanceReminderService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @MockitoBean private lateinit var outbox: PushOutboxStore
    @MockitoBean private lateinit var relay: PushOutboxRelay
    @MockitoBean private lateinit var sender: FcmPushSender

    @BeforeEach fun setup() {
        // Flyway off인 H2에는 JDBC 전용 아웃박스 테이블이 없다. 트랜잭션 참여를 확인할 저장 대역을 둔다.
        // 실제 PostgreSQL INSERT 문법은 UserNotificationPostgresTests가 검증한다.
        jdbc.execute("CREATE TABLE IF NOT EXISTS notification_outbox_probe(message_id VARCHAR(255) PRIMARY KEY)")
        cleaner.cleanAll()
        repeat(2) {
            val user = users.save(User(nickname = "원자성", marketingPushAgreedAt = Instant.EPOCH))
            tokens.save(DevicePushToken(userId = user.id, token = "atomic-$it", platform = PushPlatform.ANDROID))
        }
        var inserted = 0
        doAnswer { call ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(count("user_notifications")).isEqualTo(2)
            val result = jdbc.update("INSERT INTO notification_outbox_probe(message_id) VALUES (?)", call.getArgument<PushMessage>(0).messageId)
            if (++inserted == 2) {
                assertThat(count("notification_outbox_probe")).isEqualTo(2)
                error("outbox failed after insert")
            }
            result
        }.`when`(outbox).insert(
            any(PushMessage::class.java) ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = ""),
            any(Instant::class.java) ?: Instant.EPOCH)
    }

    @Test fun `캠페인은 실제 아웃박스 삽입 실패 때 내역과 발송 요청을 모두 롤백하고 FAILED로 남긴다`() {
        val campaign = campaigns.save(PushCampaign(title = "원자성", body = "본문", scheduledAt = Instant.EPOCH))
        assertThat(promotion.sendDue(Instant.now()).single().failed).isTrue()
        assertThat(campaigns.findById(campaign.id).orElseThrow().status).isEqualTo(PushCampaignStatus.FAILED)
        assertThat(count("user_notifications")).isZero()
        assertThat(count("notification_outbox_probe")).isZero()
        verify(outbox, times(2)).insert(any(PushMessage::class.java) ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = ""), any(Instant::class.java) ?: Instant.EPOCH)
        verifyNoInteractions(sender)
    }

    @Test fun `출석은 실제 아웃박스 삽입 실패 때 내역과 발송 요청을 롤백하고 회차 실패를 기록한다`() {
        redis.delete("push:attendance-reminder:${java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))}")
        val logger = mock(StructuredLogger::class.java)
        AttendanceReminderScheduler(attendance, redis, logger).run()
        verify(logger).event(eq("attendance_reminder_failed") ?: "attendance_reminder_failed", org.mockito.ArgumentMatchers.any<Pair<String, Any?>>() ?: ("" to null))
        assertThat(count("user_notifications")).isZero()
        assertThat(count("notification_outbox_probe")).isZero()
        verify(outbox, times(2)).insert(any(PushMessage::class.java) ?: PushMessage("", "", data = emptyMap(), requestId = "", sessionId = ""), any(Instant::class.java) ?: Instant.EPOCH)
        verifyNoInteractions(sender)
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!
}
