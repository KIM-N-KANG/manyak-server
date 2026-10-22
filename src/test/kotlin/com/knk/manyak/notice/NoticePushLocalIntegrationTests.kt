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
        val notice = notices.save(Notice(title = "점검", body = "내용", createdByUserId = target.id))
        campaigns.save(PushCampaign(title = notice.title, body = notice.body, kind = PushKind.SERVICE, noticeId = notice.id, scheduledAt = Instant.EPOCH))
        val service = PromotionPushService(campaigns, users, sender, Clock.fixed(now, ZoneOffset.UTC))
        assertThat(service.sendDue(now).single().sent).isEqualTo(1)
        assertThat(sent.single().first).isEqualTo(target.id)
        assertThat(sent.single().second).containsEntry("type", "NOTICE").containsEntry("noticeId", notice.publicId.toString())
            .containsEntry("title", "점검").containsEntry("deepLink", "https://manyak.app/notices/${notice.publicId}")
    }
    @Test fun `로컬 광고성 공지는 서비스 동의와 별개로 광고와 야간 동의를 재확인한다`() {
        val target = user("야간 수신", service = false, marketing = true, night = true)
        user("주간만", marketing = true)
        user("서비스만")
        val notice = notices.save(Notice(title = "이벤트", body = "내용", createdByUserId = target.id))
        campaigns.save(PushCampaign(title = notice.title, body = notice.body, kind = PushKind.MARKETING, noticeId = notice.id, scheduledAt = Instant.EPOCH))
        val service = PromotionPushService(campaigns, users, sender, Clock.fixed(now, ZoneOffset.UTC))
        assertThat(service.sendDue(now).single().sent).isEqualTo(1)
        assertThat(sent.single().first).isEqualTo(target.id)
        assertThat(sent.single().second).containsEntry("type", "PROMOTION").containsEntry("noticeId", notice.publicId.toString())
            .containsEntry("title", "(광고) 이벤트").containsEntry("deepLink", "https://manyak.app/notices/${notice.publicId}")
    }
}
