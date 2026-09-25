package com.knk.manyak.push.outbox

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.core.env.MapPropertySource
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class PushOutboxSchedulingTests {
    @Test
    fun `릴레이가 브로커를 기다려도 기본 단일 스레드 스케줄러는 다른 작업을 실행한다`() {
        val started = CountDownLatch(1)
        val otherTaskRan = CountDownLatch(1)
        val delivery = CompletableFuture<Unit>()
        val now = Instant.parse("2026-09-25T00:00:00Z")
        val settings = PushOutboxProperties()
        val store = mock(PushOutboxStore::class.java)
        val message = PushMessage("message", "recipient", data = emptyMap(), requestId = "trace", sessionId = "session")
        `when`(store.claim(now, settings.lease, settings.batchSize))
            .thenReturn(listOf(PushOutboxRow(1, message, 0, now, now.plus(settings.lease))))
        val context = AnnotationConfigApplicationContext()
        val shared = ThreadPoolTaskScheduler().apply { poolSize = 1; setThreadNamePrefix("shared-test-") }
        try {
            context.environment.propertySources.addFirst(MapPropertySource("test", mapOf("manyak.push.mode" to "remote")))
            context.registerBean("taskScheduler", ThreadPoolTaskScheduler::class.java, Supplier { shared })
            context.registerBean(PushOutboxStore::class.java, Supplier { store })
            context.registerBean(Clock::class.java, Supplier { Clock.fixed(now, ZoneOffset.UTC) })
            context.registerBean(SimpleMeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            context.registerBean(PushPublisher::class.java, Supplier { PushPublisher { started.countDown(); delivery } })
            context.registerBean(OtherScheduledWork::class.java, Supplier { OtherScheduledWork(started, otherTaskRan) })
            context.register(OutboxSchedulingTestConfig::class.java)
            context.refresh()
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(otherTaskRan.await(2, TimeUnit.SECONDS))
                .describedAs("브로커 future가 미완료여도 다른 @Scheduled 작업은 실행되어야 한다").isTrue()
            assertThat(delivery).isNotDone()
            assertThat(shared.poolSize).isEqualTo(1)
            assertThat(context.getBeansOfType(TaskExecutor::class.java).keys).containsExactly("taskScheduler")
        } finally {
            delivery.complete(Unit)
            context.close()
        }
    }
}

class OtherScheduledWork(private val started: CountDownLatch, private val ran: CountDownLatch) {
    @Scheduled(fixedDelay = 10)
    fun run() { if (started.count == 0L) ran.countDown() }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableScheduling
@ComponentScan(
    basePackageClasses = [PushOutboxRelay::class], useDefaultFilters = false,
    includeFilters = [ComponentScan.Filter(type = FilterType.REGEX, pattern = [".*PushOutbox(Relay|Scheduler)"])],
)
class OutboxSchedulingTestConfig
