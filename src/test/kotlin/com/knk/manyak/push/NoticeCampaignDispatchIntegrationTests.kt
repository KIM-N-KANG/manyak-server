package com.knk.manyak.push

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.notice.entity.*
import com.knk.manyak.notice.repository.NoticeRepository
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.repository.*
import com.knk.manyak.push.service.*
import com.knk.manyak.push.inbox.ScheduledNotificationWriter
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@SpringBootTest(properties = ["manyak.push.mode=remote"])
class NoticeCampaignDispatchIntegrationTests {
    @Autowired private lateinit var notices: NoticeRepository
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @MockitoSpyBean private lateinit var users: UserRepository
    @MockitoBean private lateinit var sender: FcmPushSender
    @MockitoBean private lateinit var store: com.knk.manyak.push.outbox.PushOutboxStore
    @MockitoBean private lateinit var relay: com.knk.manyak.push.outbox.PushOutboxRelay
    private val messages = mutableListOf<com.knk.manyak.push.outbox.PushMessage>()
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var notifications: ScheduledNotificationWriter
    @Autowired private lateinit var outbox: ScheduledPushOutboxWriter
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val now = Instant.parse("2026-10-30T03:00:00Z")
    private lateinit var owner: User
    @BeforeEach fun setup() {
        cleaner.cleanAll(); reset(sender, users, store); messages.clear()
        doAnswer { invocation ->
            messages += invocation.getArgument<com.knk.manyak.push.outbox.PushMessage>(0)
            1
        }.`when`(store).insert(org.mockito.ArgumentMatchers.any(com.knk.manyak.push.outbox.PushMessage::class.java)
            ?: com.knk.manyak.push.outbox.PushMessage(messageId = "fixture", recipientId = "fixture", kind = "SERVICE", type = "NOTICE", data = emptyMap(), requestId = "fixture", sessionId = "fixture"),
            org.mockito.ArgumentMatchers.any(Instant::class.java) ?: Instant.EPOCH)
        owner = users.save(User(nickname = "수신 회원", marketingPushAgreedAt = Instant.EPOCH))
        tokens.save(DevicePushToken(userId = owner.id, token = "notice-target", platform = PushPlatform.ANDROID))
    }
    private fun service(remote: Boolean, at: Instant = now) = PromotionPushService(campaigns, users, sender, stories,
        Clock.fixed(at, ZoneOffset.UTC), if (remote) outbox else null, notifications = notifications, notices = notices)
    private fun notice(tag: NoticeTag = NoticeTag.SERVICE, published: Boolean = true) = notices.save(Notice(
        title = "원문", body = "원문 본문", tag = tag, status = if (published) NoticeStatus.PUBLISHED else NoticeStatus.DRAFT,
        createdByUserId = owner.id))
    private fun campaign(n: Notice) = campaigns.save(PushCampaign(title = "알림 제목", body = "알림 본문", scheduledAt = now,
        noticeId = n.id, noticeTag = n.tag, kind = n.tag.pushKind))

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `미게시 공지는 대상 조회 내역 요청 발송 없이 실패하고 게시 뒤 재실행할 수 있다`(remote: Boolean) {
        val n = notice(published = false)
        val c = campaign(n)
        val result = service(remote).sendDue(now).single()
        assertThat(result.failed).isTrue()
        assertThat(result.reason).isEqualTo("NOTICE_NOT_PUBLISHED")
        val failed = campaigns.findById(c.id).orElseThrow()
        assertThat(failed.status).isEqualTo(PushCampaignStatus.FAILED)
        assertThat(failed.failureReason).isEqualTo("NOTICE_NOT_PUBLISHED")
        assertThat(failed.targetCount).isZero(); assertThat(failed.sentCount).isZero()
        assertThat(failed.startedAt).isEqualTo(now); assertThat(failed.finishedAt).isEqualTo(now)
        verify(users, never()).findServicePushTargetIds()
        verify(users, never()).findMarketingPushTargetIds()
        verifyNoInteractions(sender)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isZero()
        assertThat(messages).isEmpty()
        verifyNoInteractions(store)
        campaigns.changeStatus(c.publicId, PushCampaignStatus.FAILED, PushCampaignStatus.SCHEDULED)
        assertThat(campaigns.findById(c.id).orElseThrow().failureReason).isNull()
        assertThat(service(remote, now.plusSeconds(1)).sendDue(now.plusSeconds(1)).single().reason).isEqualTo("NOTICE_NOT_PUBLISHED")
        n.status = NoticeStatus.PUBLISHED; notices.saveAndFlush(n)
        campaigns.changeStatus(c.publicId, PushCampaignStatus.FAILED, PushCampaignStatus.SCHEDULED)
        assertThat(service(remote, now.plusSeconds(2)).sendDue(now.plusSeconds(2)).single().failed).isFalse()
        assertThat(campaigns.findById(c.id).orElseThrow().failureReason).isNull()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(1)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `선점은 현재 태그로 종류를 정하며 같은 공지의 두 캠페인은 내역 두 건이다`(remote: Boolean) {
        users.save(User(nickname = "광고 미동의", servicePushEnabled = true))
        val n = notice()
        val first = campaign(n)
        n.tag = NoticeTag.EVENT; notices.saveAndFlush(n)
        val result = service(remote).sendDue(now).single()
        assertThat(result.kind).isEqualTo(PushKind.MARKETING)
        verify(users).findMarketingPushTargetIds()
        verify(users, never()).findServicePushTargetIds()
        if (remote) {
            assertThat(messages.single().type).isEqualTo("PROMOTION")
            assertThat(messages.single().kind).isEqualTo("MARKETING")
            assertThat(messages.single().data).containsEntry("noticeTag", "EVENT").containsEntry("title", "(광고) 알림 제목")
        }
        val saved = campaigns.findById(first.id).orElseThrow()
        assertThat(saved.kind).isEqualTo(PushKind.MARKETING); assertThat(saved.noticeTag).isEqualTo(NoticeTag.EVENT)
        assertThat(jdbc.queryForList("SELECT type,notice_tag FROM user_notifications").single())
            .containsEntry("type", "PROMOTION").containsEntry("notice_tag", "EVENT")
        val second = campaign(n)
        service(remote, now.plusSeconds(1)).sendDue(now.plusSeconds(1))
        val rows = jdbc.queryForList("SELECT message_key,notice_public_id FROM user_notifications")
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it["message_key"] }.distinct()).hasSize(2)
        assertThat(rows.map { it["notice_public_id"] }).containsOnly(n.publicId)
        n.tag = NoticeTag.PROMOTION; notices.saveAndFlush(n)
        val third = campaign(n)
        n.tag = NoticeTag.UPDATE; notices.saveAndFlush(n)
        service(remote, now.plusSeconds(2)).sendDue(now.plusSeconds(2))
        assertThat(campaigns.findById(first.id).orElseThrow().kind).isEqualTo(PushKind.MARKETING)
        assertThat(campaigns.findById(second.id).orElseThrow().noticeTag).isEqualTo(NoticeTag.EVENT)
        assertThat(campaigns.findById(third.id).orElseThrow().noticeTag).isEqualTo(NoticeTag.UPDATE)
        assertThat(jdbc.queryForList("SELECT type FROM user_notifications", String::class.java))
            .containsExactlyInAnyOrder("PROMOTION", "PROMOTION", "NOTICE", "NOTICE")
    }

    @Test fun `선점 판정 뒤 게시 해제는 기다리지 않고 성공하며 그 회차는 발송한다`() {
        val n = notice()
        val c = campaign(n)
        val targets = users.findServicePushTargetIds()
        doAnswer {
            n.status = NoticeStatus.DRAFT
            notices.saveAndFlush(n)
            targets
        }.`when`(users).findServicePushTargetIds()
        val result = service(false).sendDue(now).single()
        assertThat(result.failed).isFalse()
        assertThat(campaigns.findById(c.id).orElseThrow().status).isEqualTo(PushCampaignStatus.SENT)
        assertThat(notices.findById(n.id).orElseThrow().status).isEqualTo(NoticeStatus.DRAFT)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(1)
    }

    @Test fun `미게시 실패 로그는 실패 사유와 대상 및 요청 0을 남긴다`() {
        campaign(notice(published = false))
        val logger = org.slf4j.LoggerFactory.getLogger(com.knk.manyak.global.observability.StructuredLogger::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            com.knk.manyak.push.scheduler.PromotionPushScheduler(service(false), com.knk.manyak.global.observability.StructuredLogger(), Clock.fixed(now, ZoneOffset.UTC)).run()
            assertThat(appender.list.map { it.formattedMessage }).anySatisfy {
                assertThat(it).contains("event_name=promotion_push_failed", "reason=NOTICE_NOT_PUBLISHED", "targets=0", "sent=0")
            }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test fun `이전 선점의 종류와 실패 기록은 재선점한 회차를 덮어쓰지 않는다`() {
        val c = campaign(notice())
        campaigns.claim(c.id, now)
        campaigns.finishIfOwned(c.id, now, PushCampaignStatus.FAILED, 0, 0, now)
        campaigns.changeStatus(c.publicId, PushCampaignStatus.FAILED, PushCampaignStatus.SCHEDULED)
        val next = now.plusSeconds(1)
        campaigns.claim(c.id, next)
        assertThat(campaigns.refreshNoticeIfOwned(c.id, now, PushKind.MARKETING, NoticeTag.EVENT)).isZero()
        assertThat(campaigns.finishIfOwned(c.id, now, PushCampaignStatus.FAILED, 0, 0, now, "NOTICE_NOT_PUBLISHED")).isZero()
        val row = campaigns.findById(c.id).orElseThrow()
        assertThat(row.status).isEqualTo(PushCampaignStatus.SENDING)
        assertThat(row.kind).isEqualTo(PushKind.SERVICE); assertThat(row.failureReason).isNull()
    }
}
