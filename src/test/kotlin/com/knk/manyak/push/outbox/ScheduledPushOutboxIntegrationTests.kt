package com.knk.manyak.push.outbox

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.*
import com.knk.manyak.push.repository.*
import com.knk.manyak.push.service.*
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.ZoneOffset
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ActiveProfiles("test")
@SpringBootTest(properties = ["manyak.push.mode=remote"])
class ScheduledPushOutboxIntegrationTests {
    @MockitoBean private lateinit var store: PushOutboxStore
    @MockitoBean private lateinit var relay: PushOutboxRelay
    @MockitoBean private lateinit var sender: FcmPushSender
    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate
    @Autowired private lateinit var notifications: com.knk.manyak.push.inbox.ScheduledNotificationWriter
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var templateRows: PushMessageTemplateRepository
    @Autowired private lateinit var templateService: PushMessageTemplateService
    @Autowired private lateinit var attendance: AttendanceReminderService
    @Autowired private lateinit var writer: ScheduledPushOutboxWriter
    @Autowired private lateinit var promotion: PromotionPushService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var campaigns: PushCampaignRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private val messages = mutableListOf<PushMessage>()

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        reset(store, sender)
        messages.clear()
        doAnswer { invocation ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            messages += invocation.getArgument<PushMessage>(0)
            1
        }.`when`(store).insert(any(PushMessage::class.java) ?: placeholder(), any(Instant::class.java) ?: Instant.EPOCH)
        repeat(3) {
            val user = users.save(User(nickname = "수신자", status = UserStatus.ACTIVE,
                marketingPushAgreedAt = Instant.EPOCH))
            tokens.save(DevicePushToken(userId = user.id, token = "token-${user.id}", platform = PushPlatform.ANDROID))
        }
    }

    @Test fun `원격 출석은 회원별 요청만 기록하고 KST 자정에 만료된다`() {
        val result = attendance.sendReminders()
        assertThat(result.sent).isEqualTo(3)
        assertThat(messages).hasSize(3)
        assertThat(jdbc.queryForList("SELECT body FROM user_notifications", String::class.java))
            .containsOnly("출석하고 받아 가세요. 오늘은 어떤 스토리를 시작해 볼까요?")
        messages.forEach {
            assertThat(it.messageId).isEqualTo("attendance:${it.recipientId}:${it.data["date"]}")
            assertThat(it.expiresAt).isEqualTo(LocalDate.parse(it.data.getValue("date"))
                .plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant())
            assertThat(it.kind).isEqualTo("MARKETING")
            assertThat(it.type).isEqualTo("ATTENDANCE_REMINDER")
            assertThat(it.data["body"]).isEqualTo("출석하고 받아 가세요. 오늘은 어떤 스토리를 시작해 볼까요?\n수신거부: 마이 > 알림 설정")
            assertThat(it.data["deepLink"]).isEqualTo("https://manyak.app/my/credits?tab=free&utm_source=push&utm_medium=push&utm_campaign=attendance_reminder&utm_content=attendance_reminder")
            assertThat(it.requestId).isEqualTo("unknown")
            assertThat(it.sessionId).isEqualTo("unknown")
        }
        verifyNoInteractions(sender)
    }

    @Test fun `원격 프로모션은 야간 미동의 회원도 요청을 기록하고 소비자에게 판정을 맡긴다`() {
        val campaign = campaigns.save(PushCampaign(title = "새 소식", body = "확인해 보세요", scheduledAt = Instant.EPOCH))
        val night = Instant.parse("2026-10-17T13:00:00Z") // KST 22:00
        val service = PromotionPushService(campaigns, users, sender, stories, Clock.fixed(night, ZoneOffset.UTC), writer, notifications = notifications)
        val result = service.sendDue(night).single()
        assertThat(result.sent).isEqualTo(3)
        assertThat(messages).hasSize(3)
        messages.forEach {
            assertThat(it.messageId).isEqualTo("promotion:${campaign.publicId}:${it.recipientId}")
            assertThat(it.kind).isEqualTo("MARKETING")
            assertThat(it.type).isEqualTo("PROMOTION")
            assertThat(it.data["title"]).isEqualTo("(광고) 새 소식")
            assertThat(it.data["body"]).isEqualTo("확인해 보세요\n수신거부: 마이 > 알림 설정")
            assertThat(it.data).doesNotContainKey("deepLink")
        }
        assertThat(campaigns.findById(campaign.id).orElseThrow().skippedCount).isNull()
        assertThat(campaigns.findById(campaign.id).orElseThrow().deepLinkIncluded).isFalse()
        verifyNoInteractions(sender)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["", " \n\t"])
    fun `원격 캠페인도 이미 붙은 광고 표기와 안내를 중복하지 않는다`(suffix: String) {
        val body = "캠페인 본문\n수신거부: 마이 > 알림 설정$suffix"
        val campaign = campaigns.save(PushCampaign(title = "(광고) 소식", body = body, scheduledAt = Instant.EPOCH))
        assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(3)
        assertThat(messages).hasSize(3)
        messages.forEach {
            assertThat(it.data["title"]).isEqualTo("(광고) 소식")
            assertThat(it.data["body"]).isEqualTo(body)
        }
        val saved = campaigns.findById(campaign.id).orElseThrow()
        assertThat(saved.body).isEqualTo(body)
        saved.status = PushCampaignStatus.SCHEDULED
        campaigns.saveAndFlush(saved)
        messages.clear()
        assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(3)
        assertThat(messages).hasSize(3)
        assertThat(messages.map { it.data["body"] }).containsOnly(body)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `300자 본문은 알림함에 원문으로 저장하고 두 푸시 모드에만 안내를 붙인다`(remote: Boolean) {
        val body = "가".repeat(300)
        val campaign = campaigns.save(PushCampaign(title = "소식", body = body, scheduledAt = Instant.EPOCH))
        val day = Instant.parse("2026-10-17T00:00:00Z")
        val service = PromotionPushService(campaigns, users, sender, stories, Clock.fixed(day, ZoneOffset.UTC),
            if (remote) writer else null, notifications = notifications)
        assertThat(service.sendDue(day).single().sent).isEqualTo(3)
        val expected = "$body\n수신거부: 마이 > 알림 설정"
        assertThat(expected).hasSize(317)
        assertThat(jdbc.queryForList("SELECT body FROM user_notifications", String::class.java)).containsOnly(body)
        assertThat(campaigns.findById(campaign.id).orElseThrow().body).isEqualTo(body)
        if (remote) {
            assertThat(messages).hasSize(3)
            assertThat(messages.map { it.data["body"] }).containsOnly(expected)
            verifyNoInteractions(sender)
        } else users.findAll().forEach { user ->
            verify(sender).sendToUser(user.id, mapOf("type" to "PROMOTION", "campaignId" to campaign.publicId.toString(),
                "title" to "(광고) 소식", "body" to expected), com.google.firebase.messaging.AndroidConfig.Priority.NORMAL,
                messageId = "promotion:${campaign.publicId}:${user.publicId}")
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `300자 출석 오버라이드는 내역에 원문 제목과 본문을 저장하고 두 푸시 모드에서 온전히 전송한다`(remote: Boolean) {
        val body = "가".repeat(300)
        templateRows.saveAndFlush(PushMessageTemplate(templateKey = "attendance_reminder", title = "출석 원문", body = body,
            effectiveFrom = Instant.EPOCH, effectiveUntil = null))
        templateService.refresh()
        try {
            val day = Instant.parse("2026-10-17T00:00:00Z")
            val service = AttendanceReminderService(users, templateService, sender, Clock.fixed(day, ZoneOffset.UTC),
                outbox = if (remote) writer else null, notifications = notifications)
            assertThat(service.sendReminders().sent).isEqualTo(3)
            assertThat(jdbc.queryForList("SELECT body FROM user_notifications", String::class.java)).containsOnly(body)
            assertThat(jdbc.queryForList("SELECT title FROM user_notifications", String::class.java)).containsOnly("출석 원문")
            val expected = "$body\n수신거부: 마이 > 알림 설정"
            assertThat(expected).hasSize(317)
            if (remote) {
                assertThat(messages).hasSize(3)
                assertThat(messages.map { it.data["body"] }).containsOnly(expected)
                verifyNoInteractions(sender)
            } else users.findAll().forEach { user ->
                verify(sender).sendToUser(user.id, mapOf("type" to "ATTENDANCE_REMINDER", "date" to "2026-10-17",
                    "title" to "(광고) 출석 원문", "body" to expected,
                    "deepLink" to "https://manyak.app/my/credits?tab=free&utm_source=push&utm_medium=push&utm_campaign=attendance_reminder&utm_content=attendance_reminder"),
                    com.google.firebase.messaging.AndroidConfig.Priority.NORMAL,
                    expiresAt = Instant.parse("2026-10-17T15:00:00Z"), messageId = "attendance:${user.publicId}:2026-10-17")
            }
        } finally {
            templateRows.deleteAll()
            templateService.refresh()
        }
    }

    @Test fun `회차의 딥링크 기록은 재실행에서 참을 거짓으로 바꾸지 않는다`() {
        val campaign=campaigns.save(PushCampaign(title="누적 링크",body="본문",scheduledAt=Instant.EPOCH))
        val data=mapOf("type" to "PROMOTION","campaignId" to campaign.publicId.toString(),"title" to "(광고) 제목","body" to "본문")
        notifications.record(data + mapOf("storyId" to java.util.UUID.randomUUID().toString(),"deepLink" to "https://manyak.app/stories/test"),
            "promotion",campaign.publicId.toString(),writer)
        assertThat(campaigns.findById(campaign.id).orElseThrow().deepLinkIncluded).isTrue()
        notifications.record(data,"promotion",campaign.publicId.toString(),writer)
        assertThat(campaigns.findById(campaign.id).orElseThrow().deepLinkIncluded).isTrue()
    }

    @Test fun `새 요청이 없으면 링크 여부를 추정하지 않고 기존 null을 유지한다`() {
        val campaign = campaigns.save(PushCampaign(title="중복 회차", body="본문", scheduledAt=Instant.EPOCH))
        doReturn(0).`when`(store).insert(any(PushMessage::class.java) ?: placeholder(), any(Instant::class.java) ?: Instant.EPOCH)
        notifications.record(mapOf("type" to "PROMOTION", "campaignId" to campaign.publicId.toString(),
            "title" to "제목", "body" to "본문", "deepLink" to "https://manyak.app/"),
            "promotion", campaign.publicId.toString(), writer)
        assertThat(campaigns.findById(campaign.id).orElseThrow().deepLinkIncluded).isNull()
    }

    @Test fun `원격 기록 실패는 캠페인을 실패로 두고 같은 행의 재예약을 허용한다`() {
        val campaign = campaigns.save(PushCampaign(title = "새 소식", body = "확인", scheduledAt = Instant.EPOCH))
        var attempts = 0
        doAnswer {
            if (++attempts == 2) error("outbox failed")
            1
        }.`when`(store).insert(any(PushMessage::class.java) ?: placeholder(), any(Instant::class.java) ?: Instant.EPOCH)
        val failed = promotion.sendDue(Instant.now()).single()
        assertThat(failed.failed).isTrue()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications", Long::class.java)).isZero()
        assertThat(failed.sent).isZero()
        val saved = campaigns.findById(campaign.id).orElseThrow()
        assertThat(saved.status).isEqualTo(PushCampaignStatus.FAILED)
        assertThat(saved.deepLinkIncluded).isNull()
        assertThat(saved.targetCount).isEqualTo(3)
        assertThat(saved.sentCount).isZero()
        assertThat(promotion.sendDue(Instant.now())).isEmpty()

        saved.status = PushCampaignStatus.SCHEDULED
        campaigns.saveAndFlush(saved)
        assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(3)
        assertThat(campaigns.findById(campaign.id).orElseThrow().status).isEqualTo(PushCampaignStatus.SENT)
        verifyNoInteractions(sender)
    }

    @Test fun `원격 출석 기록 실패는 성공 결과를 반환하지 않는다`() {
        doThrow(IllegalStateException("outbox failed")).`when`(store)
            .insert(any(PushMessage::class.java) ?: placeholder(), any(Instant::class.java) ?: Instant.EPOCH)
        assertThatThrownBy { attendance.sendReminders() }.isInstanceOf(IllegalStateException::class.java)
        verifyNoInteractions(sender)
    }


    @Autowired private lateinit var notices: com.knk.manyak.notice.repository.NoticeRepository

    @Test fun `공지 캠페인은 종류별 동의자에게 공개 UUID와 딥링크 및 시나리오별 메시지 ID를 기록한다`() {
        val all = users.findAll()
        all.first().servicePushEnabled = false
        users.saveAndFlush(all.first())
        val serviceOnly = users.save(User(nickname = "서비스만", servicePushEnabled = true))
        tokens.save(DevicePushToken(userId = serviceOnly.id, token = "service-only", platform = PushPlatform.ANDROID))
        for (kind in listOf(com.knk.manyak.push.dto.PushKind.SERVICE, com.knk.manyak.push.dto.PushKind.MARKETING)) {
            val notice = notices.saveAndFlush(com.knk.manyak.notice.entity.Notice(title = "공지", body = "평문", tag = if (kind == com.knk.manyak.push.dto.PushKind.SERVICE) com.knk.manyak.notice.entity.NoticeTag.UPDATE else com.knk.manyak.notice.entity.NoticeTag.EVENT, createdByUserId = all.first().id))
            val campaign = campaigns.save(PushCampaign(title = "공지 제목", body = "공지 알림", scheduledAt = Instant.EPOCH, kind = kind, noticeId = notice.id, noticeTag = notice.tag))
            messages.clear()
            assertThat(promotion.sendDue(Instant.now()).single().sent).isEqualTo(3)
            assertThat(messages).hasSize(3)
            assertThat(campaigns.findById(campaign.id).orElseThrow().deepLinkIncluded).isTrue()
            val type = if (kind == com.knk.manyak.push.dto.PushKind.SERVICE) "NOTICE" else "PROMOTION"
            val prefix = if (type == "NOTICE") "notice" else "promotion"
            for (message in messages) {
                assertThat(message.type).isEqualTo(type)
                assertThat(message.kind).isEqualTo(kind.name)
                assertThat(message.messageId).isEqualTo("$prefix:${campaign.publicId}:${message.recipientId}")
                assertThat(message.data["noticeTag"]).isEqualTo(notice.tag.name)
                assertThat(message.data["noticeId"]).isEqualTo(notice.publicId.toString())
                assertThat(message.data["deepLink"]).isEqualTo("https://manyak.app/notices/${notice.publicId}?utm_source=push&utm_medium=push&utm_campaign=${campaign.publicId}&utm_content=${type.lowercase()}")
                assertThat(message.data["title"]).isEqualTo(if (type == "NOTICE") "공지 제목" else "(광고) 공지 제목")
                assertThat(message.data["body"]).isEqualTo(if (type == "NOTICE") "공지 알림" else "공지 알림\n수신거부: 마이 > 알림 설정")
                assertThat(message.requestId).isEqualTo("unknown")
                assertThat(message.sessionId).isEqualTo("unknown")
            }
            assertThat(messages.map { it.recipientId }).contains(if (type == "NOTICE") serviceOnly.publicId.toString() else all.first().publicId.toString())
            assertThat(messages.map { it.recipientId }).doesNotContain(if (type == "NOTICE") all.first().publicId.toString() else serviceOnly.publicId.toString())
        }
        verifyNoInteractions(sender)
    }

    private fun placeholder() = PushMessage("", "", data = emptyMap(), requestId = "", sessionId = "")
}
