package com.knk.manyak.chat.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component

/** 첫 disabled 한 건도 increase()로 감지하도록 기동 시 0으로 등록한다. */
@Component
class ChatCompactMeters : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        Counter.builder(ChatCompactService.METRIC_COMPACT_RESULT)
            .description("대화 요약 결과")
            .tag("outcome", ChatCompactService.OUTCOME_DISABLED)
            .register(registry)
    }
}
