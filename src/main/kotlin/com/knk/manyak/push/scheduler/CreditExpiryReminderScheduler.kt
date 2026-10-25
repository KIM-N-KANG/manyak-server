package com.knk.manyak.push.scheduler

import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.service.CreditExpiryReminderService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** 매일 10:00 KST에 날짜 키를 선점한 인스턴스만 이프 만료 예정 회차를 실행한다. */
@Component
@ConditionalOnProperty(
    name = ["manyak.push.credit-expiry-reminder.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CreditExpiryReminderScheduler(
    private val creditExpiryReminderService: CreditExpiryReminderService,
    private val redisTemplate: StringRedisTemplate,
    private val structuredLogger: StructuredLogger,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Scheduled(
        cron = "\${manyak.push.credit-expiry-reminder.cron:0 0 10 * * *}",
        zone = "Asia/Seoul",
    )
    fun run() {
        // 예외를 절대 밖으로 내보내지 않는다: 스케줄러가 한 번의 예외로 이후 실행을 멈추면 안 된다.
        try {
            val today = LocalDate.now(clock.withZone(SEOUL_ZONE))
            if (!claimToday(today)) {
                return
            }
            val result = creditExpiryReminderService.sendReminders()
            structuredLogger.event(
                "credit_expiry_reminder_sent",
                "targets" to result.targets,
                "cooldown" to result.cooldown,
                "sent" to result.sent,
            )
        } catch (exception: Exception) {
            structuredLogger.event(
                "credit_expiry_reminder_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
    }

    /**
     * 오늘 회차를 선점한다. 이미 누가 잡았거나 Redis를 쓸 수 없으면 false(건너뛴다).
     * TTL 24시간은 날짜가 넘어가면 키가 자연 만료하게 해, 지우는 운영 작업을 만들지 않기 위한 것이다.
     */
    private fun claimToday(today: LocalDate): Boolean =
        try {
            redisTemplate.opsForValue().setIfAbsent("$CLAIM_KEY_PREFIX$today", CLAIMED, CLAIM_TTL) == true
        } catch (exception: Exception) {
            // 선점 여부를 모르면 중복을 막기 위해 회차를 건너뛴다.
            structuredLogger.event(
                "credit_expiry_reminder_claim_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
            false
        }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val CLAIM_KEY_PREFIX = "push:credit-expiry-reminder:"
        const val CLAIMED = "1"
        val CLAIM_TTL: Duration = Duration.ofHours(24)
    }
}
