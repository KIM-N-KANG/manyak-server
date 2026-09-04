package com.knk.manyak.push.scheduler

import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.service.AttendanceReminderService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * 출석 리마인드 푸시를 하루 한 번(09:00 KST) 실행한다(KNK-1116).
 *
 * `manyak.push.attendance-reminder.enabled`로 켜고 끈다(기본 켬, 테스트 프로파일은 끔). 시각은
 * `...cron`으로 조정한다.
 *
 * **같은 날 1회 보장은 Redis `SET NX`가 한다.** `@Scheduled`는 인스턴스마다 도는데, 배포 교체로 옛 태스크와
 * 새 태스크가 cron 시각에 걸치면 두 인스턴스가 같은 회차를 돌아 회원이 알림을 두 번 받는다. 날짜 키를 먼저
 * 선점한 인스턴스만 발송한다.
 *
 * ponytail: 회원별 발송 기록 테이블은 두지 않는다. 회차 중간에 인스턴스가 죽으면 그날 남은 회원은 알림을
 * 놓친다(다음 날 회차로 자연 복구). 광고성 알림이라 중복 발송이 누락보다 나쁘고, 그 한 가지를 막는 데
 * 날짜 키 하나면 충분하다. 재개가 필요해지면 그때 회원별 기록을 둔다.
 *
 * Redis 장애로 선점 여부를 알 수 없으면 **발송을 건너뛴다** — 같은 이유(중복보다 누락)다.
 */
@Component
@ConditionalOnProperty(
    name = ["manyak.push.attendance-reminder.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class AttendanceReminderScheduler(
    private val attendanceReminderService: AttendanceReminderService,
    private val redisTemplate: StringRedisTemplate,
    private val structuredLogger: StructuredLogger,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Scheduled(
        cron = "\${manyak.push.attendance-reminder.cron:0 0 9 * * *}",
        zone = "Asia/Seoul",
    )
    fun run() {
        // 예외를 절대 밖으로 내보내지 않는다: 스케줄러가 한 번의 예외로 이후 실행을 멈추면 안 된다.
        try {
            val today = LocalDate.now(clock.withZone(SEOUL_ZONE))
            if (!claimToday(today)) {
                return
            }
            val result = attendanceReminderService.sendReminders()
            structuredLogger.event(
                "attendance_reminder_sent",
                "targets" to result.targets,
                "sent" to result.sent,
                // 조회 이후 자격을 잃어 건너뛴 수(동의 철회·정지·탈퇴·야간). 0이 아닌 값이 계속 보이면
                // 회차가 길어져 재확인에 걸리는 회원이 많다는 신호다.
                "skipped" to result.skipped,
            )
        } catch (exception: Exception) {
            structuredLogger.event(
                "attendance_reminder_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
    }

    /**
     * 오늘 회차를 선점한다. 이미 누가 잡았거나 Redis를 쓸 수 없으면 false(건너뛴다).
     * TTL 24시간은 날짜가 넘어가면 키가 자연 소멸하게 해, 지우는 운영 작업을 만들지 않기 위한 것이다.
     */
    private fun claimToday(today: LocalDate): Boolean =
        try {
            redisTemplate.opsForValue().setIfAbsent("$CLAIM_KEY_PREFIX$today", CLAIMED, CLAIM_TTL) == true
        } catch (exception: Exception) {
            // 선점 여부를 모르는 채 보내면 인스턴스 수만큼 중복 발송된다. 광고성이라 누락을 택한다.
            structuredLogger.event(
                "attendance_reminder_claim_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
            false
        }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val CLAIM_KEY_PREFIX = "push:attendance-reminder:"
        const val CLAIMED = "1"
        val CLAIM_TTL: Duration = Duration.ofHours(24)
    }
}
