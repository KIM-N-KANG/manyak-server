package com.knk.manyak.global.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스프링 스케줄링 활성화. 현재는 크레딧 대사 배치([com.knk.manyak.credit.scheduler.CreditReconciliationScheduler])가 쓴다.
 *
 * 실제 스케줄 태스크는 각 컴포넌트의 @Scheduled에 있으며, 개별 config로 켜고 끈다(예: 대사는 manyak.credit.reconciliation.enabled).
 */
@Configuration
@EnableScheduling
class SchedulingConfig
