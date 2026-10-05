package com.knk.manyak.story.submission

import com.knk.manyak.global.observability.MdcKeys
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

@Component
class SubmissionPoller(
    private val store: SubmissionClaimStore,
    private val runner: SubmissionExecutor,
    @Qualifier("storyModerationExecutor") private val executor: Executor,
    @Value("\${manyak.ai.moderation.pool-size:4}") poolSize: Int,
    @Value("\${manyak.ai.moderation.reclaim-after:300s}") private val lease: Duration,
    @Value("\${manyak.ai.moderation.timeout:180s}") timeout: Duration,
    @Value("\${manyak.ai.moderation.poll-enabled:true}") private val enabled: Boolean,
) {
    init {
        require(poolSize > 0)
        require(lease > timeout) { "Moderation lease must exceed AI timeout" }
    }
    // 실행기에는 이 폴러만 제출한다. 제출 직전부터 작업 종료까지 슬롯을 예약하므로 큐 대기는 없다.
    private val slots = Semaphore(poolSize)
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${manyak.ai.moderation.poll-interval:1000}")
    fun tick() { if (enabled) poll() }

    @Synchronized
    fun poll() {
        val count = slots.availablePermits()
        if (count == 0 || !slots.tryAcquire(count)) return
        val rows = try { store.claim(Instant.now(), lease, count) } catch (ex: Exception) {
            slots.release(count)
            throw ex
        }
        slots.release(count - rows.size)
        rows.forEach { row ->
            val previous = MDC.getCopyOfContextMap()
            try {
                MDC.put(MdcKeys.REQUEST_ID, UUID.randomUUID().toString())
                executor.execute { try { runner.run(row) } finally { slots.release() } }
            } catch (ex: RuntimeException) {
                slots.release()
                try { store.release(row) } catch (_: Exception) { /* DB 장애면 임대 만료 후 다시 선점한다. */ }
                log.warn("moderation_dispatch_deferred submission={} attempt={} error={}", row.id, row.attempt, ex.javaClass.simpleName)
            } finally {
                if (previous != null) MDC.setContextMap(previous) else MDC.clear()
            }
        }
    }
}
