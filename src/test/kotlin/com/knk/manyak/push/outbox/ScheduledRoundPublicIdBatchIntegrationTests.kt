package com.knk.manyak.push.outbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.dto.CreditExpiryTarget
import com.knk.manyak.push.inbox.ScheduledNotificationWriter
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.support.PushRemoteIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * 원격 회차 기록과 이프 만료 알림함 기록의 회원 공개 식별자 변환(KNK-2056 A-O).
 *
 * 결과(요청의 수신자와 메시지 id, 알림함 메시지 키, 대상이 사라졌을 때의 예외)를 고정하고, 회원 조회가 대상마다가 아니라
 * 묶음 단위로 나가는지 리포지토리 호출 수로 확인한다.
 */
class ScheduledRoundPublicIdBatchIntegrationTests : PushRemoteIntegrationTest() {
    @Autowired private lateinit var store: PushOutboxStore
    @Autowired private lateinit var writer: ScheduledPushOutboxWriter
    @Autowired private lateinit var notifications: ScheduledNotificationWriter
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private val messages = mutableListOf<PushMessage>()
    private val data = mapOf("type" to "PROMOTION", "title" to "제목", "body" to "본문")

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        reset(store)
        messages.clear()
        doAnswer { invocation -> messages += invocation.getArgument<PushMessage>(0); 1 }
            .`when`(store).insert(any(PushMessage::class.java) ?: placeholder(), any(Instant::class.java) ?: Instant.EPOCH)
    }

    private fun members(count: Int) = (1..count).map { users.save(User(nickname = "수신$it")) }

    /** 회원 읽기 호출(이름이 find로 시작) 수. 저장 호출은 세지 않는다. */
    private fun userReads() = mockingDetails(users).invocations.count { it.method.name.startsWith("find") }

    @Test fun `원격 회차 기록은 대상 순서대로 회원 공개 식별자로 요청을 하나씩 만든다`() {
        val targets = members(3)
        val recorded = writer.record(targets.map { it.id }, data, "promotion", "camp-1")
        assertThat(recorded).isEqualTo(3)
        assertThat(messages.map { it.recipientId }).containsExactlyElementsOf(targets.map { it.publicId.toString() })
        assertThat(messages.map { it.messageId }).containsExactlyElementsOf(targets.map { "promotion:camp-1:${it.publicId}" })
    }

    @Test fun `대상 회원이 사라졌으면 Optional과 같은 예외로 회차를 실패시킨다`() {
        val targets = members(2)
        assertThatThrownBy { writer.record(listOf(targets[0].id, Long.MAX_VALUE, targets[1].id), data, "promotion", "camp-1") }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test fun `원격 회차 기록의 회원 조회는 대상 수와 무관하게 한 번이다`() {
        val targets = members(20)
        clearInvocations(users)
        writer.record(targets.map { it.id }, data, "promotion", "camp-1")
        assertThat(messages).hasSize(20)
        assertThat(userReads()).isEqualTo(1)
    }

    @Test fun `대상이 묶음 크기를 넘으면 상한 있는 묶음으로 나눠 조회한다`() {
        val targets = members(501)
        clearInvocations(users)
        writer.record(targets.map { it.id }, data, "promotion", "camp-1")
        assertThat(messages.map { it.recipientId }).containsExactlyElementsOf(targets.map { it.publicId.toString() })
        assertThat(userReads()).isEqualTo(2)
    }

    @Test fun `이프 만료 알림함 기록은 회원별 메시지 키를 남기고 회원 조회는 한 번이다`() {
        val targets = members(4)
        clearInvocations(users)
        val expiry = targets.map { target(it.id) }
        notifications.recordCreditExpiry(expiry, emptyList(), data + ("deepLink" to "https://manyak.app/my"), "2026-11-12", null)
        assertThat(jdbc.queryForList("SELECT message_key FROM user_notifications ORDER BY user_id", String::class.java))
            .containsExactlyElementsOf(targets.map { "credit-expiry:${it.publicId}:2026-11-12" })
        assertThat(userReads()).isEqualTo(1)
    }

    @Test fun `이프 만료 알림함 대상이 사라졌으면 같은 예외로 실패하고 내역을 남기지 않는다`() {
        val targets = members(1)
        assertThatThrownBy {
            notifications.recordCreditExpiry(listOf(target(targets[0].id), target(Long.MAX_VALUE)), emptyList(),
                data + ("deepLink" to "https://manyak.app/my"), "2026-11-12", null)
        }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isZero()
    }

    private fun target(id: Long) = object : CreditExpiryTarget {
        override val userId = id
        override val expiresAt: Instant = Instant.parse("2026-11-20T00:00:00Z")
    }

    private fun placeholder() = PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
}
