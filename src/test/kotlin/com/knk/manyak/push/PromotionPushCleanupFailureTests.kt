package com.knk.manyak.push

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.scheduler.PromotionPushScheduler
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PromotionPushService
import com.knk.manyak.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PromotionPushCleanupFailureTests {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `시간 초과 조회나 갱신 실패를 기록하고 같은 틱에서 예약을 선점해 발송한다`(updateFails: Boolean) {
        val now = Instant.parse("2026-10-22T03:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val campaigns = mock(PushCampaignRepository::class.java)
        val users = mock(UserRepository::class.java)
        val sender = mock(FcmPushSender::class.java)
        val expired = PushCampaign(id = 1, title = "만료", body = "본문", scheduledAt = now,
            status = PushCampaignStatus.SENDING, startedAt = now.minusSeconds(1800))
        val scheduled = PushCampaign(id = 2, title = "예약", body = "본문", scheduledAt = now)
        val error = DataAccessResourceFailureException("cleanup unavailable")
        if (updateFails) {
            `when`(campaigns.findByStatusAndStartedAtLessThanEqual(PushCampaignStatus.SENDING, now.minusSeconds(1800)))
                .thenReturn(listOf(expired))
            `when`(campaigns.finishIfOwned(expired.id, expired.startedAt!!, PushCampaignStatus.FAILED, null, null, now))
                .thenThrow(error)
        } else {
            `when`(campaigns.findByStatusAndStartedAtLessThanEqual(PushCampaignStatus.SENDING, now.minusSeconds(1800)))
                .thenThrow(error)
        }
        `when`(campaigns.findDueScheduled(now)).thenReturn(listOf(scheduled))
        `when`(campaigns.claim(scheduled.id, now)).thenReturn(1)
        `when`(campaigns.finishIfOwned(scheduled.id, now, PushCampaignStatus.SENT, 0, 0, now)).thenReturn(1)
        val logger = LoggerFactory.getLogger(StructuredLogger::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            PromotionPushScheduler(PromotionPushService(campaigns, users, sender, mock(StoryRepository::class.java), clock, notifications = com.knk.manyak.push.inbox.ScheduledNotificationWriter(users, mock(com.knk.manyak.push.inbox.UserNotificationStore::class.java), "https://manyak.app", clock, mock(PushCampaignRepository::class.java))), StructuredLogger(), clock).run()
            verify(campaigns).claim(scheduled.id, now)
            verify(users).findMarketingPushTargetIds()
            verify(campaigns).finishIfOwned(scheduled.id, now, PushCampaignStatus.SENT, 0, 0, now)
            assertThat(appender.list.map { it.formattedMessage }).anySatisfy {
                assertThat(it).contains("event_name=promotion_push_failed", "error=cleanup unavailable")
            }.anySatisfy {
                assertThat(it).contains("event_name=promotion_push_sent", "campaignId=${scheduled.publicId}")
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
