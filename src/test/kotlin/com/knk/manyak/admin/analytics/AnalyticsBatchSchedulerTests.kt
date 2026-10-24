package com.knk.manyak.admin.analytics

import com.knk.manyak.global.observability.StructuredLogger
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.*
import java.util.concurrent.*

class AnalyticsBatchSchedulerTests {
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-22T18:02:00Z"))
    private val runner = mock(AnalyticsBatchRunner::class.java)
    private val langfuse = mock(LangfuseCostSnapshots::class.java)
    private val redis = mock(StringRedisTemplate::class.java)
    @Suppress("UNCHECKED_CAST") private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val events = CopyOnWriteArrayList<Map<String, Any?>>()
    private val log = object : StructuredLogger() {
        override fun event(eventName: String, fields: Map<String, Any?>) { assertThat(eventName).isEqualTo("admin_analytics_backfill"); events.add(fields) }
    }
    private val slots = ConcurrentHashMap.newKeySet<String>()
    init {
        `when`(redis.opsForValue()).thenReturn(values)
        `when`(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofMinutes(10)))).thenAnswer { slots.add(it.getArgument(0)) }
        `when`(runner.run()).thenReturn(AnalyticsBatchResult("nightly", "done", keys = 1, fetchedDays = 1, cost = 1))
    }
    private fun scheduler() = AnalyticsBatchScheduler(runner, redis, log, clock, langfuse = langfuse)
    @Test fun `두 인스턴스의 같은 KST5분 슬롯은 SET NX 승자만 조회하고 기록한다`() {
        val pool = Executors.newFixedThreadPool(2)
        try { listOf(scheduler(), scheduler()).map { s -> pool.submit { s.run() } }.forEach { it.get(2, TimeUnit.SECONDS) } }
        finally { pool.shutdownNow() }
        verify(runner, times(1)).run(); assertThat(events).hasSize(1)
        assertThat(slots.single()).endsWith("2026-10-23T03:00")
        clock.at = clock.at.plusSeconds(300); scheduler().run()
        verify(runner, times(2)).run()
    }
    @Test fun `Redis 예외는 claim_failed로 건너뛰고 다음 실행은 정상이며 할 일 없으면 무로그다`() {
        `when`(redis.opsForValue()).thenThrow(IllegalStateException("redis unavailable")).thenReturn(values)
        val scheduler = scheduler(); scheduler.run()
        verifyNoInteractions(runner)
        assertThat(events.single()).containsEntry("result", "skipped").containsEntry("reason", "claim_failed")
        scheduler.run(); verify(runner).run(); assertThat(events).hasSize(2)
        clock.at = clock.at.plusSeconds(300); `when`(runner.run()).thenReturn(null)
        scheduler.run(); assertThat(events).hasSize(2)
    }
    @Test fun `예상 밖 예외도 스케줄러 밖으로 나가지 않고 다음 슬롯은 실행한다`() {
        `when`(runner.run()).thenThrow(IllegalStateException("not logged")).thenReturn(AnalyticsBatchResult("backfill", "done"))
        val scheduler = scheduler()
        assertThatCode { scheduler.run() }.doesNotThrowAnyException()
        clock.at = clock.at.plusSeconds(300); scheduler.run()
        assertThat(events.map { it["result"] }).containsExactly("failed", "done")
        assertThat(events.toString()).doesNotContain("not logged")
    }
    @Test fun `이전 실행 중에는 다음 슬롯을 선점하지 않는다`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        `when`(runner.run()).thenAnswer { started.countDown(); release.await(2, TimeUnit.SECONDS); AnalyticsBatchResult("nightly", "done") }
        val pool = Executors.newSingleThreadExecutor(); val scheduler = scheduler()
        try {
            val task = pool.submit { scheduler.run() }; assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
            clock.at = clock.at.plusSeconds(300); scheduler.run()
            assertThat(slots).hasSize(1)
            release.countDown(); task.get(2, TimeUnit.SECONDS)
            verify(runner, times(1)).run()
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `03시 이전에도 Amplitude 할 일과 무관하게 Langfuse 저장을 실행한다`() {
        clock.at = Instant.parse("2026-10-23T15:05:00Z")
        `when`(runner.run()).thenReturn(null)
        scheduler().run()
        verify(langfuse).refreshFinalized()
        assertThat(events).isEmpty()
    }
    @Test fun `Amplitude 예산 소진과 429와 예외가 Langfuse 실행을 막지 않는다`() {
        for (reason in listOf("budget_share", "rate_limited", "http_error")) {
            `when`(runner.run()).thenReturn(AnalyticsBatchResult("nightly", "partial", reason))
            scheduler().run()
            clock.at = clock.at.plusSeconds(300)
        }
        `when`(runner.run()).thenThrow(IllegalStateException())
        scheduler().run()
        verify(langfuse, times(4)).refreshFinalized()
    }
    @Test fun `Langfuse 설정 없음은 Amplitude를 중단하지 않으며 슬롯 미선점이면 둘 다 실행하지 않는다`() {
        doThrow(AdminAiCostUnavailable()).`when`(langfuse).refreshFinalized()
        scheduler().run()
        verify(runner).run()
        scheduler().run()
        verify(langfuse, times(1)).refreshFinalized()
    }

    @Test fun `Amplitude 단계가 먼저 끝나고 AI 비용 저장 단계가 뒤따른다`() {
        scheduler().run()
        val order = inOrder(runner, langfuse)
        order.verify(runner).run()
        order.verify(langfuse).refreshFinalized()
    }

}
