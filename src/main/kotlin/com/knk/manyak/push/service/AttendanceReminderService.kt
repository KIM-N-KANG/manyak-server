package com.knk.manyak.push.service

import com.google.firebase.messaging.AndroidConfig
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.outbox.ScheduledPushOutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 원격 모드는 기록한 요청 수, 로컬 모드는 발송기 호출 수를 집계한다. */
data class AttendanceReminderResult(val targets: Int, val sent: Int)

/** 출석 대상과 문구를 정하고, 원격 모드는 회차 전체를 아웃박스에 기록한다. */
@Service
class AttendanceReminderService(
    private val userRepository: UserRepository,
    private val pushMessageTemplateService: PushMessageTemplateService,
    private val fcmPushSender: FcmPushSender,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${manyak.push.web-base-url:https://manyak.app}")
    private val webBaseUrl: String = "https://manyak.app",
    private val outbox: ScheduledPushOutboxWriter? = null,
    private val notifications: com.knk.manyak.push.inbox.ScheduledNotificationWriter,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun sendReminders(): AttendanceReminderResult {
        val today = LocalDate.now(clock.withZone(SEOUL_ZONE))
        val expiry = today.plusDays(1).atStartOfDay(SEOUL_ZONE).toInstant()

        // 문구는 회차 시작에 한 번만 해석한다. 회원마다 읽으면 갱신이 중간에 끼어들어 같은 회차가 두 문구로 나간다.
        val text = pushMessageTemplateService.templateOf(PushTemplateKey.ATTENDANCE_REMINDER)
        val data = mapOf(
            "type" to TYPE_ATTENDANCE_REMINDER,
            "date" to today.toString(),
            // (광고) 표기는 서버가 붙인다(DB 값에 맡기지 않는다).
            "title" to withAdPrefix(text.title),
            "body" to text.body,
            "deepLink" to pushDeepLink(webBaseUrl, "/my/credits?tab=free", TYPE_ATTENDANCE_REMINDER),
        )

        val round = notifications.record(data, "attendance", today.toString(), outbox, expiry)
        val targetIds = round.pushTargetIds
        round.recordedPushes?.let { return AttendanceReminderResult(targetIds.size, it) }

        var sent = 0
        for (userId in targetIds) {
            // 로컬 모드에서만 자격을 재확인한다. 원격 모드는 소비자가 발송 직전에 확인한다.
            if (!clock.instant().isBefore(expiry)) break
            val user = userRepository.findById(userId).orElse(null)
            if (user == null || user.status != UserStatus.ACTIVE || !user.canReceiveMarketingPush(clock.instant())) {
                fcmPushSender.recordDiscarded(userId, data, "attendance:${user?.publicId}:$today",
                    if (user?.status != UserStatus.ACTIVE) "NOT_ACTIVE" else "MARKETING_NOT_AGREED")
                continue
            }
            // 한 회원의 발송 실패가 나머지 회차를 끊지 않는다. 개별 토큰 실패는 FcmPushSender가 이미 흡수한다.
            val now = clock.instant()
            if (!now.isBefore(expiry)) break
            runCatching {
                fcmPushSender.sendToUser(
                    userId, data, AndroidConfig.Priority.NORMAL, expiresAt = expiry,
                    messageId = "attendance:${user.publicId}:$today",
                )
            }
                .onSuccess { sent++ }
                .onFailure { logger.warn("출석 리마인드 발송에 실패했습니다. (userId={}, error={})", userId, it.javaClass.simpleName) }
        }
        return AttendanceReminderResult(targets = targetIds.size, sent = sent)
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** 앱이 알림 UI를 조립할 때 쓰는 시나리오 식별자(data 전용 메시지 — KNK-1130). */
        const val TYPE_ATTENDANCE_REMINDER = "ATTENDANCE_REMINDER"
    }
}
