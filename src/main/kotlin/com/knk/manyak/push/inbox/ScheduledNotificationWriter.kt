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
) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun record(
        data: Map<String, String>, scenario: String, occurrence: String,
        outbox: ScheduledPushOutboxWriter?, expiresAt: Instant? = null, kind: String = "MARKETING",
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
        store.insertScheduled(NotificationContent(type, data.getValue("title"), data.getValue("body"),
            inboxDeepLink(base, path, type, data["campaignId"]), storyId, noticeId, data["noticeTag"]?.let(com.knk.manyak.notice.entity.NoticeTag::valueOf)), scenario, occurrence, clock.instant())
        val sent = outbox?.record(targetIds, data, scenario, occurrence, expiresAt, kind)
        return NotificationRound(targetIds, sent)
    }
}
