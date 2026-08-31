package com.knk.manyak.credit.scheduler

import com.knk.manyak.credit.service.CreditPolicyService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 크레딧 정책 오버라이드 스냅샷을 주기적으로 다시 적재한다(KNK-1056).
 *
 * 갱신을 요청 경로 밖으로 빼는 게 목적이다. 예전에는 읽을 때 TTL이 지났으면 그 자리에서 조회했는데, 그러면
 * 크레딧 경로의 `@Transactional` 안에서 DB I/O가 일어나 (1) 조회 실패가 바깥 트랜잭션을 rollback-only로 찍거나
 * (2) `REQUIRES_NEW`로 그걸 피하면 바깥 커넥션을 쥔 채 두 번째 커넥션을 기다렸다. 지금은 스케줄러 스레드에서만
 * 조회하므로 읽기는 순수 메모리 연산이다([CreditPolicyService.amountOf]).
 *
 * `manyak.credit.policy-refresh.enabled`로 켜고 끈다(기본 켬, 테스트 프로파일은 끔 — 테스트는 오버라이드를 넣고
 * [CreditPolicyService.refresh]를 직접 호출해 반영 시점을 스스로 정한다).
 * 첫 적재는 부팅 시 [CreditPolicyService.preloadAndLogOnStartup]가 이미 하므로 초기 지연을 주기와 같게 둔다.
 */
@Component
@ConditionalOnProperty(
    name = ["manyak.credit.policy-refresh.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class CreditPolicyRefreshScheduler(
    private val creditPolicyService: CreditPolicyService,
) {

    @Scheduled(
        fixedDelayString = "\${manyak.credit.policy-refresh.interval-ms:60000}",
        initialDelayString = "\${manyak.credit.policy-refresh.interval-ms:60000}",
    )
    fun run() {
        // refresh는 예외를 밖으로 내보내지 않는다(scheduleWithFixedDelay는 한 번의 예외로 이후 실행을 영구 중단한다).
        creditPolicyService.refresh()
    }
}
