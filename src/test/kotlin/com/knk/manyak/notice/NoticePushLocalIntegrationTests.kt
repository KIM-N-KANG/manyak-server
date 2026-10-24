package com.knk.manyak.notice

import com.google.firebase.messaging.AndroidConfig
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.notice.entity.Notice
import com.knk.manyak.notice.repository.NoticeRepository
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.repository.*
import com.knk.manyak.push.service.*
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@SpringBootTest(properties = ["manyak.push.mode=local"])
class NoticePushLocalIntegrationTests {
    @MockitoBean private lateinit var sender: FcmPushSender
    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    @Autowired private lateinit var notifications: com.knk.manyak.push.inbox.ScheduledNotificationWriter
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var notices: NoticeRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private val now = Instant.parse("2026-10-22T13:00:00Z") // KST 야간
    private val sent = mutableListOf<Pair<Long, Map<String, String>>>()
    @BeforeEach fun setup() {
        cleaner.cleanAll(); sent.clear()
        doAnswer { call ->
            assertThat(call.getArgument<AndroidConfig.Priority>(2)).isEqualTo(AndroidConfig.Priority.NORMAL)
            sent += call.getArgument<Long>(0) to call.getArgument<Map<String, String>>(1)
            null
        }.`when`(sender).sendToUser(anyLong(), anyMap(), any(AndroidConfig.Priority::class.java) ?: AndroidConfig.Priority.NORMAL, isNull(), isNull(), org.mockito.ArgumentMatchers.any<String>())
    }
    private fun user(name: String, service: Boolean = true, marketing: Boolean = false, night: Boolean = false, status: UserStatus = UserStatus.ACTIVE): User {
        val user = users.save(User(nickname = name, servicePushEnabled = service, status = status,
            marketingPushAgreedAt = if (marketing) Instant.EPOCH else null, marketingPushNightAgreedAt = if (night) Instant.EPOCH else null))
        tokens.save(DevicePushToken(userId = user.id, token = "notice-${user.id}", platform = PushPlatform.ANDROID))
        return user
    }
    @Test fun `로컬 정보성 공지는 서비스 동의자에게 야간에도 광고 표기 없이 보통 우선순위로 보낸다`() {
        val target = user("서비스 수신")
        user("서비스 거부", service = false, marketing = true)
        user("정지", status = UserStatus.SUSPENDED)
        val notice = notices.save(Notice(title = "점검", body = "내용", tag = com.knk.manyak.notice.entity.NoticeTag.UPDATE, createdByUserId = target.id))
        val campaign = campaigns.save(PushCampaign(title = notice.title, body = notice.body, kind = PushKind.SERVICE, noticeId = notice.id, noticeTag = notice.tag, scheduledAt = Instant.EPOCH))
        val service = PromotionPushService(campaigns, users, sender, stories, Clock.fixed(now, ZoneOffset.UTC), notifications = notifications)
        assertThat(service.sendDue(now).single().sent).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForList("SELECT notice_tag FROM user_notifications", String::class.java)).containsOnly(notice.tag.name)
        assertThat(jdbc.queryForList("SELECT deep_link FROM user_notifications", String::class.java))
            .containsOnly("https://manyak.app/notices/${notice.publicId}?utm_source=inbox&utm_medium=inbox&utm_campaign=${campaign.publicId}&utm_content=notice")
        assertThat(sent.single().first).isEqualTo(target.id)
        assertThat(sent.single().second).containsEntry("type", "NOTICE").containsEntry("noticeId", notice.publicId.toString())
            .containsEntry("noticeTag", "UPDATE").containsEntry("title", "점검").containsEntry("deepLink", "https://manyak.app/notices/${notice.publicId}?utm_source=push&utm_medium=push&utm_campaign=${campaign.publicId}&utm_content=notice")
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["이벤트", "(광고) 이벤트"])
    fun `로컬 광고성 공지는 동의와 태그 및 광고 중복을 확인한다`(title: String) {
        val target = user("야간 수신", service = false, marketing = true, night = true)
        user("주간만", marketing = true)
        user("서비스만")
        val notice = notices.save(Notice(title = title, body = "내용", tag = com.knk.manyak.notice.entity.NoticeTag.EVENT, createdByUserId = target.id))
        val campaign = campaigns.save(PushCampaign(title = notice.title, body = notice.body, kind = PushKind.MARKETING, noticeId = notice.id, noticeTag = notice.tag, scheduledAt = Instant.EPOCH))
        val service = PromotionPushService(campaigns, users, sender, stories, Clock.fixed(now, ZoneOffset.UTC), notifications = notifications)
        assertThat(service.sendDue(now).single().sent).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForList("SELECT notice_tag FROM user_notifications", String::class.java)).containsOnly(notice.tag.name)
        assertThat(jdbc.queryForList("SELECT deep_link FROM user_notifications", String::class.java))
            .containsOnly("https://manyak.app/notices/${notice.publicId}?utm_source=inbox&utm_medium=inbox&utm_campaign=${campaign.publicId}&utm_content=promotion")
        assertThat(sent.single().first).isEqualTo(target.id)
        assertThat(jdbc.queryForList("SELECT title FROM user_notifications", String::class.java)).containsOnly("(광고) 이벤트")
        assertThat(notices.findById(notice.id).orElseThrow().title).isEqualTo(title)
        assertThat(sent.single().second).containsEntry("type", "PROMOTION").containsEntry("noticeId", notice.publicId.toString())
            .containsEntry("noticeTag", "EVENT").containsEntry("title", "(광고) 이벤트").containsEntry("deepLink", "https://manyak.app/notices/${notice.publicId}?utm_source=push&utm_medium=push&utm_campaign=${campaign.publicId}&utm_content=promotion")
    }
}
