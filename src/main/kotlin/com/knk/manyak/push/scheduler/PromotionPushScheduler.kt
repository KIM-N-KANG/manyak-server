package com.knk.manyak.push.scheduler

import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.push.service.PromotionPushService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 도래한 프로모션 캠페인을 1분마다 훑어 보낸다(KNK-1117).
 *
 * `manyak.push.promotion.enabled`로 켜고 끈다(기본 켬, 테스트 프로파일은 끔). 예약은 사람이 SQL로 넣으므로
 * 분 단위 정밀도면 충분하다.
 *
 * **중복 발송 방지 장치를 여기에 두지 않는다.** 출석 리마인드는 Redis `SET NX`로 날짜를 선점하지만, 이쪽은
 * 선점 대상이 이미 DB 행이라 조건부 UPDATE가 그 역할을 한다([PromotionPushService]).
 */
@Component
@ConditionalOnProperty(
    name = ["manyak.push.promotion.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PromotionPushScheduler(
    private val promotionPushService: PromotionPushService,
    private val structuredLogger: StructuredLogger,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Scheduled(fixedDelayString = "\${manyak.push.promotion.interval-ms:60000}")
    fun run() {
        // 예외를 절대 밖으로 내보내지 않는다: 스케줄러가 한 번의 예외로 이후 실행을 멈추면 안 된다.
        try {
            promotionPushService.sendDue(clock.instant()).forEach { result ->
                structuredLogger.event(
                    if (result.failed) "promotion_push_failed" else "promotion_push_sent",
                    "campaignId" to result.campaignId.toString(),
                    "targets" to result.targets,
                    "sent" to result.sent,
                    // 조회 이후 자격을 잃어 건너뛴 수. 야간 캠페인에서는 야간 미동의자가 여기 잡힌다.
                    "skipped" to result.skipped,
                )
            }
        } catch (exception: Exception) {
            structuredLogger.event(
                "promotion_push_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
    }
}
