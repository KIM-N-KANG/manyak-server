package com.knk.manyak.admin.analytics

import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
@ConditionalOnProperty(name = ["manyak.admin.analytics.batch.enabled"], havingValue = "true", matchIfMissing = true)
class AnalyticsBatchScheduler(private val runner: AnalyticsBatchRunner, private val redis: StringRedisTemplate,
    private val log: StructuredLogger, private val clock: Clock = Clock.systemUTC(), private val policy: AnalyticsBatchPolicy = AnalyticsBatchPolicy(),
    private val langfuse: LangfuseCostSnapshots) {
    private val costLog = org.slf4j.LoggerFactory.getLogger(javaClass)
    private val running = java.util.concurrent.atomic.AtomicBoolean()

    @Scheduled(cron = "\${manyak.admin.analytics.batch.cron:0 */5 * * * *}", zone = "Asia/Seoul")
    fun run() {
        if (!running.compareAndSet(false, true)) return
        val started = System.nanoTime()
        val now = clock.instant().atZone(com.knk.manyak.admin.dto.AdminDateRange.KST)
        val phase = if (now.toLocalTime() >= policy.nightlyStart) "nightly" else "backfill"
        fun record(result: AnalyticsBatchResult) = log.event("admin_analytics_backfill", mapOf(
            "phase" to result.phase, "result" to result.result, "reason" to result.reason, "keys" to result.keys,
            "fetched_days" to result.fetchedDays, "remaining_days" to result.remainingDays, "cost" to result.cost,
            "duration_ms" to (System.nanoTime() - started) / 1_000_000))
        try {
            val slot = now.toLocalDateTime().withMinute(now.minute / 5 * 5).withSecond(0).withNano(0)
            val claimed = try {
                redis.opsForValue().setIfAbsent("admin:analytics:batch:$slot", "1", java.time.Duration.ofMinutes(10)) == true
            } catch (_: Exception) {
                record(AnalyticsBatchResult(phase, "skipped", "claim_failed"))
                return
            }
            if (!claimed) return
            try { runner.run()?.let { record(it) } }
            catch (_: Exception) { record(AnalyticsBatchResult(phase, "failed", "http_error")) }
            // 03:00 전, Amplitude 작업 없음·예산 소진·429에도 비용 보존은 실행한다.
            // 두 시스템은 호출 횟수/예산이 다르며 Langfuse는 화면 요청의 직렬화·대기를 공유한다.
            try { langfuse.refreshFinalized() }
            catch (_: AdminAiCostUnavailable) { /* 설정 없음 로그와 쿨다운은 snapshots에서 처리한다. */ }
            catch (_: Exception) { costLog.warn("admin_ai_cost_fetch_failed reason=http_error status=null") }
        } finally { running.set(false) }
    }
}
