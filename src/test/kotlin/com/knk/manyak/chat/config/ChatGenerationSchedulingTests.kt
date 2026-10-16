package com.knk.manyak.chat.config

import com.knk.manyak.chat.service.ChatGenerationDeadlines
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ChatGenerationSchedulingTests {
    @Test
    fun `채팅 마감 스케줄러가 Boot 기본 taskScheduler를 대체하지 않는다`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration::class.java))
            .withUserConfiguration(ChatSseConfig::class.java, ChatGenerationDeadlines::class.java, ChatDeadlineSchedulingTestConfig::class.java)
            .run { context ->
                assertThat(context.getBeansOfType(TaskScheduler::class.java).keys).containsExactly("taskScheduler")
                assertThat(context.getBeansOfType(ScheduledExecutorService::class.java)).isEmpty()
            }
    }

    @Test
    fun `실제 마감 스레드가 요청별 MDC와 관측 문맥을 보존한다`() {
        val scheduler = ChatGenerationDeadlines()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
            })
        }
        val accessor = ObservationThreadLocalAccessor.getInstance()
        val previousRegistry = accessor.observationRegistry
        accessor.setObservationRegistry(registry)
        val requestThread = Thread.currentThread()
        try {
            repeat(2) { index ->
                val parent = Observation.start("request-$index", registry)
                val seen = CompletableFuture<Pair<Map<String, String>, Observation?>>()
                try {
                    MDC.setContextMap(mapOf("request_id" to "req_$index", "session_id" to "session_$index", "device_id_hash" to "device_$index"))
                    val expected = MDC.getCopyOfContextMap()
                    parent.openScope().use {
                        scheduler.schedule({
                            assertThat(Thread.currentThread()).isNotSameAs(requestThread)
                            seen.complete((MDC.getCopyOfContextMap() ?: emptyMap()) to registry.currentObservation)
                        }, 10)
                    }
                    MDC.clear()
                    val (mdc, observation) = seen.get(5, TimeUnit.SECONDS)
                    assertThat(mdc).isEqualTo(expected)
                    assertThat(observation).isSameAs(parent)
                } finally {
                    MDC.clear()
                    parent.stop()
                }
            }
        } finally {
            scheduler.shutdown()
            accessor.setObservationRegistry(previousRegistry)
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableScheduling
class ChatDeadlineSchedulingTestConfig
