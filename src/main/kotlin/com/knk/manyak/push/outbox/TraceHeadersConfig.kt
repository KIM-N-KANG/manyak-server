package com.knk.manyak.push.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/** 추적이 켜진 경우에만 Tracer·Propagator 빈이 있다. 꺼져 있으면 아무것도 쓰지 않는 구현을 준다(KNK-1552). */
@Configuration(proxyBeanMethods = false)
class TraceHeadersConfig {
    @Bean
    fun traceHeaders(tracer: ObjectProvider<Tracer>, propagator: ObjectProvider<Propagator>): TraceHeaders {
        val t = tracer.ifAvailable ?: return TraceHeaders.NONE
        val p = propagator.ifAvailable ?: return TraceHeaders.NONE
        return TraceHeaders { sink ->
            val context = t.currentSpan()?.context() ?: return@TraceHeaders
            p.inject(context, null) { _, name, value -> sink(name, value) }
        }
    }

    /** 요청이나 작업의 스팬 안에서 발송 요청을 기록할 때 기록 스팬의 추적 헤더를 만든다(KNK-2097). */
    @Bean
    fun outboxTraceRecorder(tracer: ObjectProvider<Tracer>, propagator: ObjectProvider<Propagator>): OutboxTraceRecorder {
        val t = tracer.ifAvailable ?: return OutboxTraceRecorder.NONE
        val p = propagator.ifAvailable ?: return OutboxTraceRecorder.NONE
        return TracingOutboxTraceRecorder(t, p)
    }

    /** 기본 추적 핸들러(Boot 순서 LOWEST_PRECEDENCE - 1000)보다 먼저 아웃박스 발행 관측을 가져가도록 가장 앞에 둔다. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun pushOutboxPublishTracingHandler(tracer: ObjectProvider<Tracer>) =
        PushOutboxPublishTracingHandler(tracer.getIfAvailable { Tracer.NOOP })
}
