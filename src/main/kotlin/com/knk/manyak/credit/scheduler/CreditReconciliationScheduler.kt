package com.knk.manyak.credit.scheduler

import com.knk.manyak.credit.service.CreditReconciliationService
import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 크레딧 선차감 대사를 주기적으로 실행한다(스펙 §4-3-7, KNK-448).
 *
 * `manyak.credit.reconciliation.enabled`로 켜고 끈다(기본 켬, 테스트 프로파일은 끔). 주기·초기 지연은 config로 조정한다.
 * [Scheduled]는 `fixedDelay`라 이전 실행이 끝난 뒤에야 다음 실행이 잡혀 겹치지 않는다(단일 인스턴스 내 직렬화).
 * 다중 인스턴스 동시 실행(배포 교체 등)은 [CreditReconciliationService]의 지갑 락 재확인이 초과 환불을 막는다.
 */
@Component
@ConditionalOnProperty(
    name = ["manyak.credit.reconciliation.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CreditReconciliationScheduler(
    private val creditReconciliationService: CreditReconciliationService,
    private val structuredLogger: StructuredLogger,
) {

    @Scheduled(
        fixedDelayString = "\${manyak.credit.reconciliation.interval-ms:900000}",
        initialDelayString = "\${manyak.credit.reconciliation.initial-delay-ms:60000}",
    )
    fun run() {
        // 예외를 절대 밖으로 내보내지 않는다: scheduleWithFixedDelay는 태스크가 한 번이라도 예외를 던지면
        // 이후 실행을 영구 중단하므로(JDK 계약), 여기서 삼켜 다음 회차가 계속 돌게 한다(멱등이라 재시도 안전).
        try {
            val result = creditReconciliationService.reconcile()
            // 실제로 보정한 경우만 남긴다(빈 실행 로그로 노이즈를 만들지 않는다).
            if (result.refundsEmitted > 0) {
                structuredLogger.event(
                    "credit_reconciliation_refunded",
                    "groups_scanned" to result.groupsScanned,
                    "refunds_emitted" to result.refundsEmitted,
                )
            }
        } catch (exception: Exception) {
            structuredLogger.event(
                "credit_reconciliation_failed",
                "error" to (exception.message ?: exception::class.simpleName ?: "unknown"),
            )
        }
    }
}
