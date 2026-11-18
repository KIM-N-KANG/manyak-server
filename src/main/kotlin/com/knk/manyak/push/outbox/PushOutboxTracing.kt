package com.knk.manyak.push.outbox

import io.micrometer.observation.Observation
import io.micrometer.tracing.Link
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.TracingObservationHandler
import io.micrometer.tracing.propagation.Propagator

/** 발송 요청을 기록한 스팬의 W3C 추적 헤더. 아웃박스 행에 저장하고 발행 때 메시지 속성에 그대로 싣는다(NOTIFICATION 5-4-6). */
data class OutboxTraceHeaders(val traceParent: String, val traceState: String?) {
    fun asMap(): Map<String, String> = buildMap {
        put(TRACEPARENT, traceParent)
        traceState?.let { put(TRACESTATE, it) }
    }

    companion object {
        const val TRACEPARENT = "traceparent"
        const val TRACESTATE = "tracestate"
        private val FORMAT = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})")

        /** 저장된 값을 (traceId, spanId, sampled)로 푼다. 형식이 다르면 링크하지 않는다. */
        fun parse(traceParent: String): Triple<String, String, Boolean>? = FORMAT.matchEntire(traceParent)?.destructured
            ?.let { (traceId, spanId, flags) -> Triple(traceId, spanId, flags.toInt(16) and 1 == 1) }
    }
}

/**
 * 발송 요청 1건을 기록할 때 기록 스팬(`push.outbox.create`, PRODUCER)을 열고 닫아 그 추적 헤더를 돌려준다.
 * 현재 스팬이 없으면(스케줄러 회차) 스팬을 만들지 않고 null이다.
 */
fun interface OutboxTraceRecorder {
    fun record(): OutboxTraceHeaders?

    companion object {
        val NONE = OutboxTraceRecorder { null }
    }
}

class TracingOutboxTraceRecorder(private val tracer: Tracer, private val propagator: Propagator) : OutboxTraceRecorder {
    override fun record(): OutboxTraceHeaders? {
        val parent = tracer.currentSpan() ?: return null
        val span = tracer.spanBuilder().setParent(parent.context()).name(CREATE).kind(Span.Kind.PRODUCER).start()
        try {
            val headers = mutableMapOf<String, String>()
            propagator.inject(span.context(), headers) { carrier, key, value -> carrier!![key] = value }
            val traceParent = headers[OutboxTraceHeaders.TRACEPARENT] ?: return null
            return OutboxTraceHeaders(traceParent, headers[OutboxTraceHeaders.TRACESTATE]?.takeIf { it.isNotBlank() })
        } finally {
            span.end()
        }
    }

    companion object {
        const val CREATE = "push.outbox.create"
    }
}

/** 릴레이의 발행 1건 관측. 메트릭(`push.outbox.publish`)은 그대로 두고 스팬만 [PushOutboxPublishTracingHandler]가 만든다. */
class PushOutboxPublishContext(val stored: OutboxTraceHeaders?) : Observation.Context()

/**
 * 릴레이 스팬을 부모 없이 연다. 저장된 추적 헤더가 있으면 기록 스팬에 링크하고 종류는 CLIENT,
 * 없으면 릴레이 스팬이 생성 문맥이 되므로 PRODUCER다(NOTIFICATION 5-4-6, 결정 22).
 * 기본 추적 핸들러보다 먼저 등록해야 이 관측을 가져간다.
 * DefaultTracingObservationHandler를 상속하면 Boot가 그 타입의 빈이 이미 있다고 보고 기본 핸들러를 만들지 않아
 * 다른 모든 관측의 스팬이 사라진다. 그래서 인터페이스를 직접 구현하고 종료는 기본 핸들러와 같은 순서로 한다.
 */
class PushOutboxPublishTracingHandler(private val tracer: Tracer) : TracingObservationHandler<PushOutboxPublishContext> {
    override fun getTracer() = tracer

    override fun supportsContext(context: Observation.Context) = context is PushOutboxPublishContext

    override fun onStart(context: PushOutboxPublishContext) {
        val stored = context.stored?.traceParent?.let(OutboxTraceHeaders::parse)
        val builder = tracer.spanBuilder().setNoParent().name(context.name ?: "push.outbox.publish")
            .kind(if (stored != null) Span.Kind.CLIENT else Span.Kind.PRODUCER)
        stored?.let { (traceId, spanId, sampled) ->
            builder.addLink(Link(tracer.traceContextBuilder().traceId(traceId).spanId(spanId).sampled(sampled).build()))
        }
        getTracingContext(context).span = builder.start()
    }

    override fun onError(context: PushOutboxPublishContext) {
        context.error?.let { getRequiredSpan(context).error(it) }
    }

    override fun onStop(context: PushOutboxPublishContext) {
        val span = getRequiredSpan(context)
        tagSpan(context, span)
        span.name(getSpanName(context))
        endSpan(context, span)
    }
}
