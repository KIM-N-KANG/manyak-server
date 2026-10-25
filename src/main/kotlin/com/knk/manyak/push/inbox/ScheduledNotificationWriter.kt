package com.knk.manyak.push.inbox

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class NotificationRound(val pushTargetIds: List<Long>, val recordedPushes: Int?)

/** 대상 조회·내역의 INSERT SELECT·원격 아웃박스를 같은 스냅샷/트랜잭션에 기록한다. */
@Service
class ScheduledNotificationWriter(
    private val users: UserRepository,
    private val store: UserNotificationStore,
    @Value("\${manyak.push.web-base-url:https://manyak.app}") private val base: String,
    private val clock: Clock = Clock.systemUTC(),
    private val campaigns: com.knk.manyak.push.repository.PushCampaignRepository,
) {
    /** 이프 안내도 알림함과 회원별 유효 기한의 발송 요청을 회차 전체로 커밋한다. */
    @Transactional
    fun recordCreditExpiry(
        inboxTargets: List<com.knk.manyak.credit.repository.CreditExpiryTarget>,
        pushTargets: List<com.knk.manyak.credit.repository.CreditExpiryTarget>,
        data: Map<String, String>, occurrence: String, outbox: ScheduledPushOutboxWriter?,
    ): NotificationRound {
        val now = clock.instant()
        val type = NotificationType.CREDIT_EXPIRY_REMINDER
        for (target in inboxTargets) {
            val user = users.findById(target.userId).orElseThrow()
            store.insert(UserNotification(userId = user.id, messageKey = "credit-expiry:${user.publicId}:$occurrence",
                type = type, title = data.getValue("title"), body = data.getValue("body"),
                deepLink = data.getValue("deepLink"), createdAt = now))
        }
        val targetIds = pushTargets.map { it.userId }
        val sent = outbox?.record(targetIds, data, "credit-expiry", occurrence, kind = "SERVICE",
            expiresAtByUserId = pushTargets.associate { it.userId to it.expiresAt })
        return NotificationRound(targetIds, sent)
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun record(
        data: Map<String, String>, scenario: String, occurrence: String,
        outbox: ScheduledPushOutboxWriter?, expiresAt: Instant? = null, kind: String = "MARKETING",
        inboxBody: String = data.getValue("body"),
        inboxTitle: String = data.getValue("title"),
        onTargetsSelected: (Int) -> Unit = {},
    ): NotificationRound {
        val targetIds = when (scenario) {
            "attendance" -> users.findAttendanceReminderTargetIds(occurrence)
            "notice" -> users.findServicePushTargetIds()
            "promotion" -> users.findMarketingPushTargetIds()
            else -> error("Unknown scheduled notification scenario: $scenario")
        }
        // 실패한 회차도 기존 캠페인 target_count를 보고할 수 있게 조회 직후 전달한다.
        onTargetsSelected(targetIds.size)
        val type = NotificationType.valueOf(data.getValue("type"))
        val storyId = data["storyId"]?.let(UUID::fromString)
        val noticeId = data["noticeId"]?.let(UUID::fromString)
        val path = when {
            type == NotificationType.ATTENDANCE_REMINDER -> "/my/credits?tab=free"
            noticeId != null -> "/notices/$noticeId"
            storyId != null -> "/stories/$storyId"
            else -> null
        }
        // 광고 표기와 수신거부 안내는 전송용이다. 알림함에는 원문 제목과 본문을 저장한다.
        store.insertScheduled(NotificationContent(type, inboxTitle, inboxBody,
            inboxDeepLink(base, path, type, data["campaignId"]), storyId, noticeId, data["noticeTag"]?.let(com.knk.manyak.notice.entity.NoticeTag::valueOf)), scenario, occurrence, clock.instant())
        val sent = outbox?.record(targetIds, data, scenario, occurrence, expiresAt, kind)
        // 요청이 실제 기록된 회차만 갱신한다. 로컬은 발송 전 회차 커밋에 포함하며 실패하면 함께 롤백한다.
        if ((sent ?: targetIds.size) > 0) data["campaignId"]?.let {
            campaigns.accumulateDeepLink(UUID.fromString(it), data.containsKey("deepLink"))
        }
        return NotificationRound(targetIds, sent)
    }
}
