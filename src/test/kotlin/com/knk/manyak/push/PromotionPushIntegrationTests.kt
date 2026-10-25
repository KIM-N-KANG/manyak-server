package com.knk.manyak.push

import com.google.firebase.messaging.AndroidConfig.Priority.NORMAL
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushCampaign
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.push.repository.PushCampaignRepository
import com.knk.manyak.push.service.FcmPushSender
import com.knk.manyak.push.service.PromotionPushService
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 프로모션 푸시(KNK-1117, 광고성 알림, 스펙 §4-3-5).
 *
 * 운영자가 SQL로 넣은 `SCHEDULED` 캠페인을 스케줄러가 도래 시각에 집어 광고 동의 회원 전원에게 보낸다.
 * 대상은 `ACTIVE` ∩ 광고 동의 ∩ 등록 토큰 보유이고, 야간(21~08시 KST)에 예약된 캠페인은 거부·연기가 아니라
 * **야간 동의자에게만** 나간다. 선점은 Redis가 아니라 캠페인 행의 조건부 UPDATE가 한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class PromotionPushIntegrationTests {

    @MockitoBean private lateinit var fcmPushSender: FcmPushSender

    @MockitoSpyBean private lateinit var userRepository: UserRepository

    @Autowired private lateinit var notifications: com.knk.manyak.push.inbox.ScheduledNotificationWriter
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var devicePushTokenRepository: DevicePushTokenRepository
    @Autowired private lateinit var pushCampaignRepository: PushCampaignRepository
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        reset(fcmPushSender, userRepository)
    }

    private fun saveMember(
        status: UserStatus = UserStatus.ACTIVE,
        marketingAgreed: Boolean = true,
        nightAgreed: Boolean = false,
    ): User = userRepository.save(
        User(
            nickname = "수신자",
            status = status,
            marketingPushAgreedAt = if (marketingAgreed) AGREED_AT else null,
            marketingPushNightAgreedAt = if (nightAgreed) AGREED_AT else null,
        ),
    )

    private fun saveToken(user: User) {
        devicePushTokenRepository.save(
            DevicePushToken(userId = user.id, token = "tok-${user.id}", platform = PushPlatform.ANDROID),
        )
    }

    /**
     * 회차마다 시각을 고정한 서비스를 만든다. 야간 판정은 회원을 보내는 **그 시점**의 시각으로 하므로,
     * 주입된 시스템 시계를 그대로 두면 테스트를 21시 이후에 돌릴 때 결과가 달라진다.
     */
    private fun service(clock: Clock = Clock.fixed(NOW, ZoneOffset.UTC)) =
        PromotionPushService(pushCampaignRepository, userRepository, fcmPushSender, stories, clock, notifications = notifications)

    private fun eligibleMember(nightAgreed: Boolean = false): User =
        saveMember(nightAgreed = nightAgreed).also { saveToken(it) }

    private fun saveCampaign(
        scheduledAt: Instant = NOW,
        status: PushCampaignStatus = PushCampaignStatus.SCHEDULED,
    ): PushCampaign = pushCampaignRepository.save(
        PushCampaign(
            title = "새 오리지널 스토리가 나왔어요",
            body = "이번 주 신작 5편을 지금 만나보세요.",
            scheduledAt = scheduledAt,
            status = status,
        ),
    )

    private fun reload(campaign: PushCampaign): PushCampaign =
        pushCampaignRepository.findById(campaign.id).orElseThrow()

    /** 설정 화면(KNK-1132)에서 광고 수신을 끈 것과 같은 상태를 만든다. */
    private fun withdrawMarketingConsent(userId: Long) {
        val user = userRepository.findById(userId).orElseThrow()
        user.marketingPushAgreedAt = null
        userRepository.saveAndFlush(user)
    }

    @Test
    fun `도래한 캠페인을 광고 동의 회원 전원에게 보내고 결과를 행에 남긴다`() {
        val first = eligibleMember()
        val second = eligibleMember()
        val campaign = saveCampaign()

        val results = service().sendDue(NOW)

        val expected = mapOf(
            "type" to "PROMOTION",
            "campaignId" to campaign.publicId.toString(),
            "title" to "(광고) 새 오리지널 스토리가 나왔어요",
            "body" to "이번 주 신작 5편을 지금 만나보세요.\n수신거부: 마이 > 알림 설정",
        )
        verify(fcmPushSender).sendToUser(first.id, expected, NORMAL, messageId = "promotion:${campaign.publicId}:${first.publicId}")
        verify(fcmPushSender).sendToUser(second.id, expected, NORMAL, messageId = "promotion:${campaign.publicId}:${second.publicId}")

        val result = results.single()
        assertThat(result.campaignId).isEqualTo(campaign.publicId)
        assertThat(result.targets).isEqualTo(2)
        assertThat(result.sent).isEqualTo(2)
        val saved = reload(campaign)
        assertThat(saved.status).isEqualTo(PushCampaignStatus.SENT)
        assertThat(saved.targetCount).isEqualTo(2)
        assertThat(saved.sentCount).isEqualTo(2)
        assertThat(saved.startedAt).isNotNull()
        assertThat(saved.finishedAt).isNotNull()
    }

    @Test
    fun `예약 시각이 아직 오지 않은 캠페인은 보내지 않는다`() {
        eligibleMember()
        val campaign = saveCampaign(scheduledAt = NOW.plusSeconds(600))

        assertThat(service().sendDue(NOW)).isEmpty()

        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(reload(campaign).status).isEqualTo(PushCampaignStatus.SCHEDULED)
    }

    @Test
    fun `로컬 회차는 스토리 공개 여부와 딥링크를 시작 때 한 번 판정한다`() {
        val owner = eligibleMember()
        eligibleMember()
        val story = stories.saveAndFlush(Story(title = "공개 스토리", userId = owner.id))
        val campaign = pushCampaignRepository.save(PushCampaign(title = "추천", body = "본문", scheduledAt = NOW, storyId = story.id))
        val sentData = mutableListOf<Map<String, String>>()
        doAnswer { invocation ->
            sentData += invocation.getArgument<Map<String, String>>(1).toMap()
            story.visibility = StoryVisibility.PRIVATE
            stories.saveAndFlush(story)
            null
        }.`when`(fcmPushSender).sendToUser(anyLong(), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())

        assertThat(service().sendDue(NOW).single().sent).isEqualTo(2)
        assertThat(sentData).hasSize(2)
        assertThat(sentData[0]).containsEntry("storyId", story.publicId.toString())
            .containsEntry("deepLink", "https://manyak.app/stories/${story.publicId}?utm_source=push&utm_medium=push&utm_campaign=${campaign.publicId}&utm_content=promotion")
        assertThat(sentData[1]).isEqualTo(sentData[0])
        assertThat(reload(campaign).deepLinkIncluded).isTrue()
    }

    @Test
    fun `회차 시작 때 비공개인 이동 스토리는 링크 없이 발송하고 거짓으로 기록한다`() {
        val owner = eligibleMember()
        val story = stories.saveAndFlush(Story(title = "비공개 스토리", userId = owner.id, visibility = StoryVisibility.PRIVATE))
        val campaign = pushCampaignRepository.save(PushCampaign(title = "추천", body = "본문", scheduledAt = NOW, storyId = story.id))
        doAnswer { invocation ->
            assertThat(invocation.getArgument<Map<String, String>>(1)).doesNotContainKeys("storyId", "deepLink")
            null
        }.`when`(fcmPushSender).sendToUser(anyLong(), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(service().sendDue(NOW).single().sent).isEqualTo(1)
        assertThat(reload(campaign).deepLinkIncluded).isFalse()
    }

    @Test
    fun `취소된 캠페인은 도래해도 보내지 않는다`() {
        eligibleMember()
        val campaign = saveCampaign(status = PushCampaignStatus.CANCELED)

        assertThat(service().sendDue(NOW)).isEmpty()

        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(reload(campaign).status).isEqualTo(PushCampaignStatus.CANCELED)
    }

    @Test
    fun `광고 미동의와 토큰 없음과 정지 회원은 대상에서 빠진다`() {
        saveMember(marketingAgreed = false).also { saveToken(it) }
        saveMember()
        saveMember(status = UserStatus.SUSPENDED).also { saveToken(it) }
        val campaign = saveCampaign()

        val result = service().sendDue(NOW).single()

        assertThat(result.targets).isZero()
        assertThat(reload(campaign).deepLinkIncluded).isNull()
        verify(fcmPushSender, never()).sendToUser(anyLong(), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(reload(campaign).status).isEqualTo(PushCampaignStatus.SENT)
    }

    @Test
    fun `야간에 도래한 캠페인은 야간 동의 회원에게만 나간다`() {
        // 야간(21~08시 KST)이라고 캠페인을 거부·연기하지 않는다. 정책은 "야간 금지"가 아니라 "야간은 별도 동의"라,
        // 야간 동의자 대상 캠페인이 있을 수 있다(정책 KNK-1129).
        val dayOnly = eligibleMember()
        val nightAgreed = eligibleMember(nightAgreed = true)
        val campaign = saveCampaign(scheduledAt = NIGHT)

        val result = service(Clock.fixed(NIGHT, ZoneOffset.UTC)).sendDue(NIGHT).single()

        verify(fcmPushSender).sendToUser(eq(nightAgreed.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        verify(fcmPushSender, never()).sendToUser(eq(dayOnly.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(result.sent).isEqualTo(1)
        assertThat(reload(campaign).skippedCount).isNull()
    }

    @Test
    fun `회차 도중 광고 수신을 철회한 회원에게는 보내지 않는다`() {
        // 대상 조회 결과는 스냅샷이라 그대로 믿으면 회차가 도는 동안 철회한 회원에게 광고가 나간다.
        val first = eligibleMember()
        val second = eligibleMember()
        saveCampaign()
        doAnswer {
            withdrawMarketingConsent(second.id)
            null
        }.`when`(fcmPushSender).sendToUser(eq(first.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())

        val result = service().sendDue(NOW).single()

        verify(fcmPushSender).sendToUser(eq(first.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        verify(fcmPushSender, never()).sendToUser(eq(second.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(result.sent).isEqualTo(1)
    }

    @Test
    fun `같은 캠페인을 두 인스턴스가 동시에 집어도 한 번만 나간다`() {
        // 배포 교체로 태스크가 둘일 때의 창. 선점은 Redis가 아니라 캠페인 행의 조건부 UPDATE가 막는다.
        eligibleMember()
        saveCampaign()

        val ready = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit {
                    ready.countDown()
                    ready.await(5, TimeUnit.SECONDS)
                    service().sendDue(NOW)
                }
            }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        verify(fcmPushSender, times(1)).sendToUser(anyLong(), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
    }

    @Test
    fun `한 회원의 발송 실패가 나머지 회차를 끊지 않는다`() {
        val first = eligibleMember()
        val second = eligibleMember()
        val campaign = saveCampaign()
        doThrow(IllegalStateException("FCM down")).`when`(fcmPushSender).sendToUser(eq(first.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())

        service().sendDue(NOW)

        verify(fcmPushSender).sendToUser(eq(second.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(reload(campaign).status).isEqualTo(PushCampaignStatus.SENT)
    }

    @Test
    fun `대상 조회가 실패하면 캠페인을 실패로 남긴다`() {
        val campaign = saveCampaign()
        doThrow(IllegalStateException("db down")).`when`(userRepository).findMarketingPushTargetIds()

        val result = service().sendDue(NOW).single()

        assertThat(result.failed).isTrue()
        val saved = reload(campaign)
        assertThat(saved.status).isEqualTo(PushCampaignStatus.FAILED)
        assertThat(saved.finishedAt).isNotNull()
    }

    @Test
    fun `회차가 야간 경계를 넘으면 그 뒤 회원은 야간 판정을 받는다`() {
        // 회차 시작 시각 하나로 전원을 판정하면, 20:59에 시작한 캠페인이 대상이 많거나 FCM이 느려 21:00을
        // 넘길 때 야간 동의 없는 회원에게 그대로 나간다(정보통신망법 위반). 판정은 회원마다 그 시점 시각으로 한다.
        val beforeNight = eligibleMember()
        val afterNight = eligibleMember()
        saveCampaign(scheduledAt = JUST_BEFORE_NIGHT)
        val crossing = SteppingClock(listOf(JUST_BEFORE_NIGHT, JUST_BEFORE_NIGHT, JUST_AFTER_NIGHT))
        val service = PromotionPushService(pushCampaignRepository, userRepository, fcmPushSender, stories, crossing, notifications = notifications)

        val result = service.sendDue(JUST_BEFORE_NIGHT).single()

        verify(fcmPushSender).sendToUser(eq(beforeNight.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        verify(fcmPushSender, never()).sendToUser(eq(afterNight.id), anyMap(), eq(NORMAL) ?: NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
        assertThat(result.sent).isEqualTo(1)
    }

    /** 부를 때마다 다음 시각을 돌려주고, 목록이 끝나면 마지막 값을 유지한다(회차가 경계를 넘는 상황 재현). */
    private class SteppingClock(private val instants: List<Instant>) : Clock() {
        private val index = AtomicInteger(0)

        override fun instant(): Instant = instants[minOf(index.getAndIncrement(), instants.size - 1)]

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val AGREED_AT: Instant = Instant.parse("2026-09-01T00:00:00Z")

        /** 낮(12:00 KST) — 야간 판정에 걸리지 않는 기준 시각. */
        val NOW: Instant = ZonedDateTime.of(2026, 9, 10, 12, 0, 0, 0, SEOUL_ZONE).toInstant()

        /** 야간 구간(21~08시 KST) 안의 시각. */
        val NIGHT: Instant = ZonedDateTime.of(2026, 9, 10, 22, 0, 0, 0, SEOUL_ZONE).toInstant()

        /** 야간 시작 직전(20:59:50 KST) — 회차가 이 시각에 시작한다. */
        val JUST_BEFORE_NIGHT: Instant = ZonedDateTime.of(2026, 9, 10, 20, 59, 50, 0, SEOUL_ZONE).toInstant()

        /** 회차가 도는 사이 넘어간 야간 시작 직후(21:00:10 KST). */
        val JUST_AFTER_NIGHT: Instant = ZonedDateTime.of(2026, 9, 10, 21, 0, 10, 0, SEOUL_ZONE).toInstant()
    }
}
