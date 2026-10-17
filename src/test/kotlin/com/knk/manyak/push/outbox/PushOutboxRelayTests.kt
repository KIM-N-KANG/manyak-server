package com.knk.manyak.push.outbox

import com.knk.manyak.global.config.TracingNoiseFilter
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.*
import java.time.*
import java.util.concurrent.CompletableFuture

class PushOutboxRelayTests {
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val store = mock(PushOutboxStore::class.java)
    private val settings = PushOutboxProperties()
    private val meters = SimpleMeterRegistry()
    private val message = PushMessage("story-completed:request", "recipient", data = mapOf("type" to "STORY_COMPLETED"), requestId = "trace", sessionId = "session")
    private fun row(attempts: Int = 0, created: Instant = now) = PushOutboxRow(1, message, attempts, created, now.plusSeconds(60))
    private fun relay(success: Boolean) = PushOutboxRelay(store, PushPublisher {
        if (success) CompletableFuture.completedFuture(Unit)
        else CompletableFuture.failedFuture(IllegalStateException("offline"))
    }, settings, meters, Clock.fixed(now, ZoneOffset.UTC))

    @Test fun `성공하면 발행 완료로 기록한다`() {
        val row = row()
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(listOf(row))
        `when`(store.complete(row, now)).thenReturn(1)
        relay(true).poll()
        verify(store).complete(row, now)
        assertThat(meters.get("manyak.push.outbox.result").tag("outcome", "published").counter().count()).isEqualTo(1.0)
    }
    @Test fun `실패 백오프는 5 10 20초이며 5분이 상한이다`() {
        listOf(0 to 5L, 1 to 10L, 2 to 20L, 20 to 300L).forEach { (attempts, seconds) ->
            val row = row(attempts)
            `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(listOf(row))
            relay(false).poll()
            verify(store).fail(row, now.plusSeconds(seconds), false)
        }
    }
    @Test fun `포기 시각과 이후 행은 브로커가 정상이더라도 발행하지 않는다`() {
        val expired = row(created = now.minus(settings.giveUpAfter))
        val older = expired.copy(id = 2, createdAt = expired.createdAt.minusSeconds(1))
        val live = row().copy(id = 3)
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(listOf(expired, older, live))
        listOf(expired, older).forEach { `when`(store.fail(it, now, true)).thenReturn(1) }
        val publisher = mock(PushPublisher::class.java)
        `when`(publisher.publish(live.message)).thenReturn(CompletableFuture.completedFuture(Unit))

        PushOutboxRelay(store, publisher, settings, meters, Clock.fixed(now, ZoneOffset.UTC)).poll()

        verify(publisher, times(1)).publish(live.message)
        listOf(expired, older).forEach { verify(store).fail(it, now, true) }
        assertThat(meters.get("manyak.push.outbox.result").tag("outcome", "abandoned").counter().count()).isEqualTo(2.0)
    }

    @Test fun `포기 기록 실패나 소유권 상실에도 나머지 행을 발행하고 포기를 중복 집계하지 않는다`() {
        val expired = row(created = now.minus(settings.giveUpAfter))
        val lost = expired.copy(id = 2)
        val live = row().copy(id = 3)
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(listOf(expired, lost, live))
        `when`(store.fail(expired, now, true)).thenThrow(IllegalStateException("DB unavailable"))
        `when`(store.fail(lost, now, true)).thenReturn(0)
        val publisher = mock(PushPublisher::class.java)
        `when`(publisher.publish(live.message)).thenReturn(CompletableFuture.completedFuture(Unit))

        PushOutboxRelay(store, publisher, settings, meters, Clock.fixed(now, ZoneOffset.UTC)).poll()

        verify(publisher, times(1)).publish(live.message)
        verify(store).complete(live, now)
        assertThat(meters.get("manyak.push.outbox.result").tag("outcome", "abandoned").counter().count()).isZero()
    }

    @Test fun `발행마다 관측을 열고 비동기 완료까지 유지하며 스케줄 실행은 제외한다`() {
        val registry = ObservationRegistry.create()
        val stopped = mutableListOf<Observation.Context>()
        registry.observationConfig().observationPredicate(TracingNoiseFilter.predicate())
            .observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
                override fun onStop(context: Observation.Context) { stopped.add(context) }
            })
        val rows = listOf(row(), row().copy(id = 2))
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(rows)
        val observations = mutableListOf<Observation>()
        val first = CompletableFuture<Unit>()
        val failure = IllegalStateException("offline")
        val relay = PushOutboxRelay(store, PushPublisher {
            val observation = registry.currentObservation
            assertThat(observation?.context?.name).isEqualTo("push.outbox.publish")
            observations.add(observation!!)
            if (observations.size == 1) first else {
                assertThat(stopped).isEmpty()
                first.complete(Unit)
                throw failure
            }
        }, settings, meters, Clock.fixed(now, ZoneOffset.UTC), registry)

        Observation.createNotStarted("tasks.scheduled.execution", registry).observe { relay.poll() }

        assertThat(observations).hasSize(2)
        assertThat(observations[0]).isNotSameAs(observations[1])
        assertThat(stopped.map { it.name }).containsExactly("push.outbox.publish", "push.outbox.publish")
        assertThat(stopped.last().error).isSameAs(failure)
        assertThat(registry.currentObservation).isNull()
    }

    @Test fun `포기 직전 제출한 발행이 기한 뒤 실패하면 백오프하고 다음 선점에서 포기한다`() {
        val row = row(created = now.minus(settings.giveUpAfter).plusMillis(1))
        val clock = mock(Clock::class.java)
        `when`(clock.instant()).thenReturn(now, now, now.plusSeconds(1))
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(listOf(row))
        val publisher = PushPublisher { CompletableFuture.failedFuture<Unit>(IllegalStateException("offline")) }

        PushOutboxRelay(store, publisher, settings, meters, clock).poll()

        verify(store).fail(row, now.plusSeconds(6), false)
    }

    @Test fun `배치 전체를 먼저 제출한 뒤 결과를 기다린다`() {
        val rows = listOf(row(), row().copy(id = 2))
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(rows)
        val first = CompletableFuture<Unit>()
        var calls = 0
        val relay = PushOutboxRelay(store, PushPublisher {
            calls++
            if (calls == 1) first else { first.complete(Unit); CompletableFuture.completedFuture(Unit) }
        }, settings, meters, Clock.fixed(now, ZoneOffset.UTC))
        relay.poll()
        rows.forEach { verify(store).complete(it, now) }
    }
    @Test fun `결과 카운터는 첫 폴링 전에도 0으로 등록된다`() {
        relay(true)
        listOf("published", "retry", "abandoned").forEach {
            assertThat(meters.get("manyak.push.outbox.result").tag("outcome", it).counter().count()).isZero()
        }
    }
    @Test fun `임대와 전송 제한 설정이 안전하지 않으면 거부한다`() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            PushOutboxProperties(lease = Duration.ofSeconds(20))
        }.isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            KafkaPushConfig().pushKafkaProducerFactory("localhost:9092", PushOutboxProperties(sendTimeout = Duration.ofSeconds(16)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

}
