package com.knk.manyak.push

import com.knk.manyak.auth.dto.UserStatus
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.auth.service.LockSql
import com.knk.manyak.credit.entity.CreditLot
import com.knk.manyak.credit.repository.CreditLotRepository
import com.knk.manyak.push.dto.PushPlatform
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.inbox.ScheduledNotificationWriter
import com.knk.manyak.push.outbox.PushMessage
import com.knk.manyak.push.outbox.PushOutboxStore
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.push.service.AttendanceReminderService
import com.knk.manyak.push.service.CreditExpiryReminderService
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PushMessageTemplateService
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.PushRemoteIntegrationTest
import io.micrometer.observation.ObservationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Clock
import java.time.Instant

/** 예약 회차의 기록 문장 수와 Redis 조회 수가 대상 수에 따라 늘지 않는다(KNK-2077 H6, M4). */
class PushBatchWriteCountIntegrationTests : PushRemoteIntegrationTest() {
    @Autowired private lateinit var registry: ObservationRegistry
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var lots: CreditLotRepository
    @Autowired private lateinit var wallet: com.knk.manyak.credit.service.CreditWalletService
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var text: PushMessageTemplateService
    @Autowired private lateinit var notifications: ScheduledNotificationWriter
    @Autowired private lateinit var writer: ScheduledPushOutboxWriter
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var store: PushOutboxStore
    @Autowired private lateinit var sender: FcmPushSender
    @Autowired private lateinit var attendance: AttendanceReminderService
    @Autowired private lateinit var cleaner: DatabaseCleaner

    @BeforeEach fun setup() {
        LockSql.install(registry)
        cleaner.cleanAll()
        redis.keys("push:credit-expiry-reminder:*").orEmpty().let { if (it.isNotEmpty()) redis.delete(it) }
        reset(store, sender)
        text.refresh()
    }

    private fun recorded(): List<List<PushMessage>> = mockingDetails(store).invocations
        .filter { it.method.name == "insertAll" }.map { it.getArgument(0) }

    @ParameterizedTest @ValueSource(ints = [3, 20])
    fun `이프 만료 회차는 대상 수와 무관하게 대상 조회, 회원 조회, 알림함 INSERT 한 번씩이고 쿨다운은 한 번에 조회한다`(n: Int) {
        val members = (1..n).map {
            users.save(User(nickname = "만료$it", status = UserStatus.ACTIVE)).also { user ->
                lots.save(CreditLot(userId = user.id, originalAmount = 10, remaining = 10, expiresAt = Instant.now().plusSeconds(3600)))
            }
        }
        val ops: ValueOperations<String, String> = spy(redis.opsForValue())
        doReturn(ops).`when`(redis).opsForValue()
        val service = CreditExpiryReminderService(wallet, AccountQueryService(users), text, sender, redis, notifications, tokens,
            Clock.systemUTC(), outbox = writer)

        val statements = LockSql.statements { service.sendReminders() }

        assertThat(statements).containsExactly("select:credit_lots", "select:users", "insert:user_notifications")
        assertThat(recorded().single().map { it.recipientId }).containsExactlyElementsOf(members.map { it.publicId.toString() })
        verify(store, never()).insert(org.mockito.Mockito.any(PushMessage::class.java) ?: placeholder(), org.mockito.Mockito.any(Instant::class.java) ?: Instant.EPOCH)
        verify(redis, never()).hasKey(anyString())
        assertThat(mockingDetails(ops).invocations.count { it.method.name == "multiGet" }).isEqualTo(1)
    }

    @ParameterizedTest @ValueSource(ints = [3, 20])
    fun `출석 회차의 발송 요청은 대상 수와 무관하게 묶음 기록 한 번이다`(n: Int) {
        val members = (1..n).map {
            users.save(User(nickname = "출석$it", status = UserStatus.ACTIVE, marketingPushAgreedAt = Instant.EPOCH)).also { user ->
                tokens.save(DevicePushToken(userId = user.id, token = "count-${user.id}", platform = PushPlatform.ANDROID))
            }
        }

        val statements = LockSql.statements { attendance.sendReminders() }

        assertThat(statements).containsExactly("select:users", "insert:user_notifications", "select:users")
        assertThat(recorded().single().map { it.recipientId }).containsExactlyInAnyOrderElementsOf(members.map { it.publicId.toString() })
        verify(store, never()).insert(org.mockito.Mockito.any(PushMessage::class.java) ?: placeholder(), org.mockito.Mockito.any(Instant::class.java) ?: Instant.EPOCH)
    }

    private fun placeholder() = PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
}
