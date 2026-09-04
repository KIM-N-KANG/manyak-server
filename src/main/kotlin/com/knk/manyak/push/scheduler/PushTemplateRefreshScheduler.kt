package com.knk.manyak.push.scheduler

import com.knk.manyak.push.service.PushMessageTemplateService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 푸시 문구 오버라이드 스냅샷을 주기적으로 다시 적재한다(KNK-1116).
 *
 * `CreditPolicyRefreshScheduler`(KNK-1056)와 같은 모양이다. 갱신을 발송 경로 밖으로 빼 읽기를 순수 메모리
 * 연산으로 유지하는 게 목적이다. 첫 적재는 부팅 시 [PushMessageTemplateService.preloadOnStartup]가 하므로
 * 초기 지연을 주기와 같게 둔다.
 *
 * `manyak.push.template-refresh.enabled`로 켜고 끈다(기본 켬, 테스트 프로파일은 끔 — 테스트는 행을 넣고
 * [PushMessageTemplateService.refresh]를 직접 호출해 반영 시점을 스스로 정한다).
 */
@Component
@ConditionalOnProperty(
    name = ["manyak.push.template-refresh.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class PushTemplateRefreshScheduler(
    private val pushMessageTemplateService: PushMessageTemplateService,
) {

    @Scheduled(
        fixedDelayString = "\${manyak.push.template-refresh.interval-ms:300000}",
        initialDelayString = "\${manyak.push.template-refresh.interval-ms:300000}",
    )
    fun run() {
        // refresh는 예외를 밖으로 내보내지 않는다(scheduleWithFixedDelay는 한 번의 예외로 이후 실행을 영구 중단한다).
        pushMessageTemplateService.refresh()
    }
}
