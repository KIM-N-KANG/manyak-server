package com.knk.manyak.push.outbox

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 커밋된 임대 아래서 브로커 발행을 기다리고, 결과만 독립 트랜잭션으로 기록한다.
 * 배치를 순차 대기하면 건수 × 전송 제한만큼 걸려 임대를 넘길 수 있으므로 한꺼번에 제출한다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
@EnableConfigurationProperties(PushOutboxProperties::class)
class PushOutboxRelay(
    private val store: PushOutboxStore,
    private val publisher: PushPublisher,
    private val settings: PushOutboxProperties,
    meters: MeterRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val counters = listOf("published", "retry", "abandoned").associateWith {
        meters.counter("manyak.push.outbox.result", "outcome", it)
    }

    fun poll() {
        val rows = store.claim(clock.instant(), settings.lease, settings.batchSize)
        // 포트는 즉시 future를 반환한다. 모든 전송을 제출하고 배치 전체에 제한 시간 하나만 적용한다.
        // 브로커 성공과 DB 완료 기록 사이 장애에는 재발행될 수 있어 소비자의 messageId 멱등 처리가 필요하다.
        val pending = rows.map { row ->
            row to try { publisher.publish(row.message) } catch (ex: Exception) { CompletableFuture.failedFuture<Unit>(ex) }
        }
        try {
            CompletableFuture.allOf(*pending.map { it.second }.toTypedArray())
                .get(settings.sendTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return // 종료 중에는 임대 만료 뒤 다른 인스턴스가 복구한다.
        } catch (_: Exception) { /* 행별 결과를 아래에서 기록한다. */ }
        pending.forEach { (row, future) ->
            try {
                val now = clock.instant()
                if (future.isDone && !future.isCompletedExceptionally && !future.isCancelled) {
                    if (store.complete(row, now) > 0) counters.getValue("published").increment()
                } else {
                    val abandoned = !now.isBefore(row.createdAt.plus(settings.giveUpAfter))
                    val backoff = settings.backoffInitial.multipliedBy(1L shl row.attempts.coerceIn(0, 30))
                        .coerceAtMost(settings.backoffMax)
                    if (store.fail(row, now.plus(backoff), abandoned) > 0) {
                        counters.getValue(if (abandoned) "abandoned" else "retry").increment()
                        if (abandoned) log.warn("푸시 아웃박스 발행 포기 (messageId={})", row.message.messageId)
                    }
                }
            } catch (ex: Exception) {
                // 한 행의 결과 저장 장애가 나머지 성공 기록을 막지 않으며, 실패한 기록은 임대 후 복구된다.
                log.warn("푸시 아웃박스 결과 기록 실패 (messageId={})", row.message.messageId, ex)
            }
        }
    }
}
