package com.knk.manyak.push.outbox

import com.knk.manyak.global.config.TracingNoiseFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.propagation.Propagator
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 아웃박스 구간의 추적 헤더와 스팬 관계를 실제 OTel SDK로 고정한다(NOTIFICATION 5-4-6, 5-5-7, KNK-2097).
 * 기록 스팬은 요청 스팬의 자식(PRODUCER)이고 그 추적 헤더가 행에 저장된다. 릴레이 스팬은 부모 없이 열리며,
 * 저장된 헤더가 있으면 기록 스팬에 링크하고(CLIENT) 그 헤더를 그대로 싣는다. 없으면 자기 헤더를 싣는다(PRODUCER).
 */
class PushOutboxTracingTests {
    private val now = Instant.parse("2026-10-17T00:00:00Z")
    private val settings = PushOutboxProperties()

    private class CollectingExporter : SpanExporter {
        val spans = CopyOnWriteArrayList<SpanData>()
        override fun export(spans: Collection<SpanData>): CompletableResultCode = CompletableResultCode.ofSuccess().also { this.spans.addAll(spans) }
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private fun withTracing(boot: Boolean = false, block: (AssertableApplicationContext, Tracer, CollectingExporter) -> Unit) {
        val exporter = CollectingExporter()
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build().use { provider ->
            val otel: OpenTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build()
            // boot: 앱처럼 Boot가 관측 핸들러를 모아 ObservationRegistry를 만들고 TraceHeadersConfig 빈을 함께 올린다.
            val configurations = listOfNotNull(
                OpenTelemetryTracingAutoConfiguration::class.java,
                ObservationAutoConfiguration::class.java.takeIf { boot },
                MicrometerTracingAutoConfiguration::class.java.takeIf { boot },
            )
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(*configurations.toTypedArray()))
                .let { if (boot) it.withUserConfiguration(TraceHeadersConfig::class.java) else it }
                .withBean(OpenTelemetry::class.java, { otel })
                .withBean(Resource::class.java, { Resource.empty() })
                .withPropertyValues("management.tracing.sampling.probability=1.0", "management.tracing.export.enabled=true")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    block(context, context.getBean(Tracer::class.java), exporter)
                }
        }
    }

    private fun recorder(context: AssertableApplicationContext) = TraceHeadersConfig().outboxTraceRecorder(
        context.getBeanProvider(Tracer::class.java), context.getBeanProvider(Propagator::class.java),
    )

    private fun row(id: Long, trace: OutboxTraceHeaders?) = PushOutboxRow(
        id, PushMessage("message-$id", "recipient", data = emptyMap(), requestId = "unknown", sessionId = "unknown"),
        0, now, now.plusSeconds(60), trace,
    )

    /** 앱과 같은 순서로 아웃박스 발행 전용 추적 핸들러를 기본 핸들러보다 먼저 둔다. */
    private fun relay(
        context: AssertableApplicationContext, tracer: Tracer, rows: List<PushOutboxRow>,
        registry: ObservationRegistry = manualRegistry(context, tracer), onSend: (SendMessageRequest) -> Unit = {},
    ): Pair<PushOutboxRelay, ObservationRegistry> {
        val client = mock(SqsAsyncClient::class.java)
        `when`(client.sendMessage(any(SendMessageRequest::class.java))).thenAnswer {
            onSend(it.getArgument(0))
            CompletableFuture.completedFuture(SendMessageResponse.builder().build())
        }
        val store = mock(PushOutboxStore::class.java)
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(rows)
        val headers = TraceHeadersConfig().traceHeaders(context.getBeanProvider(Tracer::class.java), context.getBeanProvider(Propagator::class.java))
        return PushOutboxRelay(store, SqsPushPublisher(client, ObjectMapper(), "https://sqs.example/queue"),
            settings, SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC), registry, headers) to registry
    }

    private fun manualRegistry(context: AssertableApplicationContext, tracer: Tracer) = ObservationRegistry.create().apply {
        observationConfig().observationPredicate(TracingNoiseFilter.predicate())
            .observationHandler(FirstMatchingCompositeObservationHandler(
                TraceHeadersConfig().pushOutboxPublishTracingHandler(context.getBeanProvider(Tracer::class.java)),
                DefaultTracingObservationHandler(tracer),
            ))
    }

    private fun SpanData.traceparent() = "00-$traceId-$spanId-${spanContext.traceFlags.asHex()}"

    @Test
    fun `스팬 안에서 기록한 행을 발행하면 기록 스팬의 추적 헤더를 그대로 싣고 릴레이 스팬은 기록 스팬에 링크한다`() = withTracing { context, tracer, exporter ->
        val request = tracer.nextSpan().name("http post /api/v1/stories").start()
        val stored = tracer.withSpan(request).use { recorder(context).record() }
        request.end()

        val create = exporter.spans.single { it.name == "push.outbox.create" }
        assertThat(create.kind).isEqualTo(SpanKind.PRODUCER)
        assertThat(create.traceId).isEqualTo(request.context().traceId())
        assertThat(create.parentSpanId).isEqualTo(request.context().spanId())
        assertThat(stored).isEqualTo(OutboxTraceHeaders(create.traceparent(), null))

        val sent = mutableListOf<SendMessageRequest>()
        val (relay, registry) = relay(context, tracer, listOf(row(1, stored))) { sent.add(it) }
        Observation.createNotStarted("tasks.scheduled.execution", registry).observe { relay.poll() }

        assertThat(sent.single().messageAttributes()["traceparent"]?.stringValue()).isEqualTo(stored!!.traceParent)
        assertThat(sent.single().messageAttributes()).doesNotContainKey("tracestate")
        val publish = exporter.spans.single { it.name == "push.outbox.publish" }
        assertThat(publish.kind).isEqualTo(SpanKind.CLIENT)
        assertThat(publish.parentSpanContext.isValid).isFalse()
        assertThat(publish.traceId).isNotEqualTo(create.traceId)
        assertThat(publish.links.map { it.spanContext.traceId to it.spanContext.spanId }).containsExactly(create.traceId to create.spanId)
        assertThat(tracer.currentSpan()).isNull()
    }

    @Test
    fun `Boot가 만든 ObservationRegistry에서도 아웃박스 발행 핸들러가 기본 추적 핸들러보다 먼저 관측을 가져간다`() = withTracing(boot = true) { context, tracer, exporter ->
        val request = tracer.nextSpan().name("http post /api/v1/stories").start()
        val stored = tracer.withSpan(request).use { context.getBean(OutboxTraceRecorder::class.java).record() }
        request.end()
        val create = exporter.spans.single { it.name == "push.outbox.create" }

        val (relay, registry) = relay(context, tracer, listOf(row(1, stored)), context.getBean(ObservationRegistry::class.java))
        Observation.createNotStarted("tasks.scheduled.execution", registry).observe { relay.poll() }

        val publish = exporter.spans.single { it.name == "push.outbox.publish" }
        assertThat(publish.kind).isEqualTo(SpanKind.CLIENT)
        assertThat(publish.parentSpanContext.isValid).isFalse()
        assertThat(publish.links.map { it.spanContext.spanId }).containsExactly(create.spanId)
        // 아웃박스 핸들러가 Boot 기본 추적 핸들러를 대체하면 다른 관측의 스팬이 사라진다.
        assertThat(exporter.spans.map { it.name }).contains("tasks.scheduled.execution")
    }

    @Test
    fun `저장된 tracestate도 발행 속성에 그대로 싣는다`() = withTracing { context, tracer, exporter ->
        val stored = OutboxTraceHeaders("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value")
        val sent = mutableListOf<SendMessageRequest>()
        val (relay, _) = relay(context, tracer, listOf(row(1, stored))) { sent.add(it) }
        relay.poll()

        assertThat(sent.single().messageAttributes()["traceparent"]?.stringValue()).isEqualTo(stored.traceParent)
        assertThat(sent.single().messageAttributes()["tracestate"]?.stringValue()).isEqualTo("vendor=value")
        val publish = exporter.spans.single { it.name == "push.outbox.publish" }
        assertThat(publish.links.map { it.spanContext.traceId to it.spanContext.spanId })
            .containsExactly("4bf92f3577b34da6a3ce929d0e0e4736" to "00f067aa0ba902b7")
    }

    @Test
    fun `스팬 밖에서 기록한 행은 추적 헤더를 비우고 발행마다 릴레이 스팬의 헤더를 싣는다`() = withTracing { context, tracer, exporter ->
        assertThat(recorder(context).record()).isNull()
        assertThat(exporter.spans).isEmpty()

        val sent = mutableListOf<String>()
        val (relay, registry) = relay(context, tracer, listOf(row(1, null), row(2, null))) {
            sent.add(it.messageAttributes()["traceparent"]!!.stringValue())
        }
        Observation.createNotStarted("tasks.scheduled.execution", registry).observe { relay.poll() }

        val publishes = exporter.spans.filter { it.name == "push.outbox.publish" }
        assertThat(publishes).hasSize(2).allSatisfy {
            assertThat(it.kind).isEqualTo(SpanKind.PRODUCER)
            assertThat(it.links).isEmpty()
            assertThat(it.parentSpanContext.isValid).isFalse()
        }
        assertThat(sent).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(publishes.map { it.traceparent() })
        assertThat(tracer.currentSpan()).isNull()
    }

    @Test
    fun `주변에 다른 추적 문맥이 있어도 릴레이 스팬은 부모 없이 연다`() = withTracing { context, tracer, exporter ->
        val sent = mutableListOf<String>()
        val (relay, _) = relay(context, tracer, listOf(row(1, null))) { sent.add(it.messageAttributes()["traceparent"]!!.stringValue()) }
        val ambient = Span.wrap(SpanContext.createFromRemoteParent(
            "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", TraceFlags.getSampled(),
            TraceState.builder().put("vendor", "value").build(),
        ))
        Context.current().with(ambient).makeCurrent().use { relay.poll() }

        val publish = exporter.spans.single { it.name == "push.outbox.publish" }
        assertThat(publish.traceId).isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736")
        assertThat(publish.parentSpanContext.isValid).isFalse()
        assertThat(sent).containsExactly(publish.traceparent())
    }
}
