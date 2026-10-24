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
    @Autowired private lateinit var noticeService: com.knk.manyak.notice.service.NoticeService
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
    @Test fun `게시로 만든 다섯 태그의 푸시와 내역은 직접 입력한 문구와 광고 접두를 사용한다`() {
        val target = user("알림 문구 수신", marketing = true, night = true)
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?,CURRENT_TIMESTAMP)", target.id)
        org.slf4j.MDC.put(com.knk.manyak.global.observability.MdcKeys.REQUEST_ID, "notice-text-test")
        try {
            for (tag in com.knk.manyak.notice.entity.NoticeTag.entries) {
                sent.clear()
                val notificationTitle = "직접 쓴 알림 제목 ${tag.name}"
                val notificationBody = "직접 쓴 알림 본문\n줄바꿈 유지"
                val notice = noticeService.create(target.id, com.knk.manyak.notice.dto.CreateNoticeRequest(
                    title = "원문 제목 ${tag.name}", body = "원문 본문", tag = tag,
                    notificationTitle = notificationTitle, notificationBody = notificationBody, reason = "작성"))
                noticeService.publish(target.id, notice.noticeId, com.knk.manyak.notice.dto.PublishNoticeRequest("게시"))
                val at = Instant.now().plusSeconds(1)
                val service = PromotionPushService(campaigns, users, sender, stories, Clock.fixed(at, ZoneOffset.UTC), notifications = notifications)
                assertThat(service.sendDue(at).single().sent).isEqualTo(1)
                val expectedTitle = if (tag.pushKind == PushKind.MARKETING) "(광고) $notificationTitle" else notificationTitle
                assertThat(sent.single().second).containsEntry("title", expectedTitle).containsEntry("body", notificationBody)
                val row = jdbc.queryForMap("SELECT title,body FROM user_notifications WHERE notice_public_id=?", notice.noticeId)
                assertThat(row["title"]).isEqualTo(expectedTitle); assertThat(row["body"]).isEqualTo(notificationBody)
                assertThat(notices.findByPublicId(notice.noticeId)!!.title).isEqualTo("원문 제목 ${tag.name}")
                assertThat(campaigns.findAll().single { it.noticeId == notices.findByPublicId(notice.noticeId)!!.id }.title).isEqualTo(notificationTitle)
            }
        } finally { org.slf4j.MDC.remove(com.knk.manyak.global.observability.MdcKeys.REQUEST_ID) }
    }

}
