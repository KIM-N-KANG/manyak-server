package com.knk.manyak.push

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PromotionPushService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
class CampaignLifecycleIntegrationTests {
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @MockitoSpyBean private lateinit var users: UserRepository
    @MockitoBean private lateinit var sender: FcmPushSender
    private val now = Instant.parse("2026-10-21T03:00:00Z")
    @BeforeEach fun clean() { cleaner.cleanAll(); reset(users, sender) }
    private fun service(at: Instant = now) = PromotionPushService(campaigns, users, sender, Clock.fixed(at, ZoneOffset.UTC))
    private fun row(start: Instant? = null) = campaigns.save(PushCampaign(title = "예약", body = "본문", scheduledAt = now,
        status = if (start == null) PushCampaignStatus.SCHEDULED else PushCampaignStatus.SENDING, startedAt = start,
        targetCount = 4, sentCount = 2))

    @Test fun `여러 캠페인의 선점 시각은 각 캠페인을 실제 선점한 때다`() {
        row(); row()
        var at = now
        val clock = object : Clock() {
            override fun instant() = at
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
        }
        doAnswer { at = at.plusSeconds(60); emptyList<Long>() }.`when`(users).findMarketingPushTargetIds()
        PromotionPushService(campaigns, users, sender, clock).sendDue(now)
        assertThat(campaigns.findAll().map { it.startedAt }.sortedBy { it }).containsExactly(now, now.plusSeconds(60))
    }

    @Test fun `폴링은 TTL 경과 행만 실패로 전환하고 결과를 보존한다`() {
        val expired = row(now.minusSeconds(1800))
        val sending = row(now.minusSeconds(1799))
        val logger = LoggerFactory.getLogger(PromotionPushService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            assertThat(service().sendDue(now)).isEmpty()
            val failed = campaigns.findById(expired.id).orElseThrow()
            assertThat(failed.status).isEqualTo(PushCampaignStatus.FAILED)
            assertThat(failed.targetCount).isEqualTo(4)
            assertThat(failed.sentCount).isEqualTo(2)
            assertThat(failed.finishedAt).isEqualTo(now)
            assertThat(campaigns.findById(sending.id).orElseThrow().status).isEqualTo(PushCampaignStatus.SENDING)
            assertThat(appender.list.count { it.formattedMessage.startsWith("promotion_push_timed_out") }).isEqualTo(1)
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @Test fun `옛 회차 종료는 TTL 실패와 재선점 뒤 새 회차를 덮어쓰지 않는다`() {
        val campaign = row()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { entered.countDown(); check(release.await(20, TimeUnit.SECONDS)); emptyList<Long>() }.`when`(users).findMarketingPushTargetIds()
        val pool = Executors.newSingleThreadExecutor()
        val logger = LoggerFactory.getLogger(PromotionPushService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val old = pool.submit<List<com.knk.manyak.push.service.PromotionPushResult>> { service().sendDue(now) }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue()
            val next = now.plusSeconds(1801)
            service(next).sendDue(next)
            assertThat(campaigns.findById(campaign.id).orElseThrow().status).isEqualTo(PushCampaignStatus.FAILED)
            assertThat(campaigns.changeStatus(campaign.publicId, PushCampaignStatus.FAILED, PushCampaignStatus.SCHEDULED)).isEqualTo(1)
            assertThat(campaigns.claim(campaign.id, next)).isEqualTo(1)
            release.countDown()
            assertThat(old.get(10, TimeUnit.SECONDS)).isEmpty()
            val current = campaigns.findById(campaign.id).orElseThrow()
            assertThat(current.status).isEqualTo(PushCampaignStatus.SENDING)
            assertThat(current.startedAt).isEqualTo(next)
            assertThat(current.sentCount).isEqualTo(2)
            assertThat(appender.list.count { it.formattedMessage.startsWith("promotion_push_result_discarded") }).isEqualTo(1)
        } finally { release.countDown(); pool.shutdownNow(); logger.detachAppender(appender); appender.stop() }
    }

    @Test fun `종료가 먼저 확정되면 시간 초과 전환은 상태를 바꾸지 않는다`() {
        val campaign = row(now.minusSeconds(1801))
        assertThat(campaigns.finishIfOwned(campaign.id, campaign.startedAt!!, PushCampaignStatus.SENT, 3, 3, now)).isEqualTo(1)
        assertThat(service().sendDue(now)).isEmpty()
        assertThat(campaigns.findById(campaign.id).orElseThrow().status).isEqualTo(PushCampaignStatus.SENT)
    }
}
