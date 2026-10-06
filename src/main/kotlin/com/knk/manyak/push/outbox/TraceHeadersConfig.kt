package com.knk.manyak.push.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
}
