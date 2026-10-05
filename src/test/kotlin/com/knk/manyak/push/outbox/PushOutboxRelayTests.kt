package com.knk.manyak.push.outbox

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
    @Test fun `24시간이 지난 실패는 포기한다`() {
        val row = row(created = now.minus(Duration.ofHours(24)))
        `when`(store.claim(now, settings.lease, settings.batchSize)).thenReturn(listOf(row))
        relay(false).poll()
        verify(store).fail(row, now.plusSeconds(5), true)
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
