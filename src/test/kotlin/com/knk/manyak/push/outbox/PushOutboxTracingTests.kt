package com.knk.manyak.push.outbox

import com.knk.manyak.global.config.TracingNoiseFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.propagation.Propagator
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

class PushOutboxTracingTests {
    @Test
    fun `요청 스팬 없는 스케줄 실행에서도 발행별 traceparent를 SQS 속성에 싣는다`() {
        SdkTracerProvider.builder().build().use { provider ->
            val otel: OpenTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build()
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenTelemetryTracingAutoConfiguration::class.java))
                .withBean(OpenTelemetry::class.java, { otel })
                .withBean(Resource::class.java, { Resource.empty() })
                .withPropertyValues("management.tracing.sampling.probability=1.0", "management.tracing.export.enabled=true")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val tracer = context.getBean(Tracer::class.java)
                    val headers = TraceHeadersConfig().traceHeaders(
                        context.getBeanProvider(Tracer::class.java), context.getBeanProvider(Propagator::class.java),
                    )
                    val registry = ObservationRegistry.create()
                    registry.observationConfig().observationPredicate(TracingNoiseFilter.predicate())
                        .observationHandler(DefaultTracingObservationHandler(tracer))
                    val client = mock(SqsAsyncClient::class.java)
                    val traceparents = mutableListOf<String>()
                    `when`(client.sendMessage(any(SendMessageRequest::class.java))).thenAnswer {
                        val request = it.getArgument<SendMessageRequest>(0)
                        val context = tracer.currentSpan()!!.context()
                        val traceparent = request.messageAttributes()["traceparent"]?.stringValue()
                        assertThat(traceparent).isEqualTo("00-${context.traceId()}-${context.spanId()}-01")
                        if (context.traceId() == "4bf92f3577b34da6a3ce929d0e0e4736") {
                            assertThat(request.messageAttributes()["tracestate"]?.stringValue()).isEqualTo("vendor=value")
                        }
                        traceparents.add(traceparent!!)
                        CompletableFuture.completedFuture(SendMessageResponse.builder().build())
                    }
                    val store = mock(PushOutboxStore::class.java)
                    val settings = PushOutboxProperties()
                    val now = Instant.parse("2026-10-17T00:00:00Z")
                    val rows = (1L..2L).map { id ->
                        PushOutboxRow(id, PushMessage("message-$id", "recipient", data = emptyMap(), requestId = "unknown", sessionId = "unknown"), 0, now, now.plusSeconds(60))
                    }
                    `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(rows)
                    val relay = PushOutboxRelay(store, SqsPushPublisher(client, ObjectMapper(), "https://sqs.example/queue", headers),
                        settings, SimpleMeterRegistry(), Clock.fixed(now, ZoneOffset.UTC), registry)

                    assertThat(tracer.currentSpan()).isNull()
                    Observation.createNotStarted("tasks.scheduled.execution", registry).observe { relay.poll() }

                    assertThat(traceparents).hasSize(2).doesNotHaveDuplicates()
                    assertThat(tracer.currentSpan()).isNull()
                    val parent = Span.wrap(SpanContext.createFromRemoteParent(
                        "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", TraceFlags.getSampled(),
                        TraceState.builder().put("vendor", "value").build(),
                    ))
                    Context.current().with(parent).makeCurrent().use { relay.poll() }
                    assertThat(traceparents).hasSize(4).doesNotHaveDuplicates()
                    assertThat(tracer.currentSpan()).isNull()
                }
        }
    }
}
