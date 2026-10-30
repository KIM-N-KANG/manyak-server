package com.knk.manyak.push.config

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.notice.repository.NoticeRepository
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PromotionPushService
import com.knk.manyak.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.convert.ApplicationConversionService
import java.time.Instant

class PushCampaignConfigurationTests {
    private val campaigns = mock(PushCampaignRepository::class.java)
    private fun runner() = ApplicationContextRunner()
        .withInitializer { it.beanFactory.conversionService = ApplicationConversionService.getSharedInstance() }
        .withUserConfiguration(PromotionPushService::class.java)
        .withBean(PushCampaignRepository::class.java, { campaigns })
        .withBean(UserRepository::class.java, { mock(UserRepository::class.java) })
        .withBean(FcmPushSender::class.java, { mock(FcmPushSender::class.java) })
        .withBean(StoryRepository::class.java, { mock(StoryRepository::class.java) })
        .withBean(NoticeRepository::class.java, { mock(NoticeRepository::class.java) })
        .withBean(com.knk.manyak.push.inbox.ScheduledNotificationWriter::class.java, { mock(com.knk.manyak.push.inbox.ScheduledNotificationWriter::class.java) })
        .withPropertyValues("manyak.push.promotion.enabled=false")

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "-PT1S", "not-a-duration"])
    fun `잘못된 TTL은 스케줄러가 꺼져 있어도 발송 호출 전에 기동을 거부한다`(value: String) {
        runner().withPropertyValues("manyak.push.campaign.sending-ttl=$value").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("manyak.push.campaign.sending-ttl")
            verifyNoInteractions(campaigns)
        }
    }

    @Test
    fun `TTL 기본값 30분으로 기동한다`() {
        runner().run { context ->
            assertThat(context).hasNotFailed()
            val now = Instant.parse("2026-10-22T03:00:00Z")
            context.getBean(PromotionPushService::class.java).sendDue(now)
            verify(campaigns).findByStatusAndStartedAtLessThanEqual(PushCampaignStatus.SENDING, now.minusSeconds(1800))
        }
    }

    @Test
    fun `양수 TTL을 바인딩하여 시간 초과 기준에 쓴다`() {
        runner().withPropertyValues("manyak.push.campaign.sending-ttl=PT5M").run { context ->
            assertThat(context).hasNotFailed()
            val now = Instant.parse("2026-10-22T03:00:00Z")
            context.getBean(PromotionPushService::class.java).sendDue(now)
            verify(campaigns).findByStatusAndStartedAtLessThanEqual(PushCampaignStatus.SENDING, now.minusSeconds(300))
        }
    }
}
