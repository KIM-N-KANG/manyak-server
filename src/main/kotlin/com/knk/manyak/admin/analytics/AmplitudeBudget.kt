package com.knk.manyak.admin.analytics

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@org.springframework.boot.context.properties.ConfigurationProperties("manyak.admin.analytics")
class AnalyticsPolicy(
    val budgetWindowSeconds: Long = 300, val budgetCost: Int = 500,
    val currentTtlSeconds: Long = 600, val historicalTtlSeconds: Long = 86400, val cooldownSeconds: Long = 60,
    val requestTimeoutSeconds: Long = 10, val refreshTimeoutSeconds: Long = 60,
)


/** 관리자 Dashboard REST 호출 전체가 공유하는 프로세스별 슬라이딩 예산과 동시 호출 한도. */
@Component
@org.springframework.boot.context.properties.EnableConfigurationProperties(AnalyticsPolicy::class)
class AmplitudeBudget(private val clock: Clock = Clock.systemUTC(), private val policy: AnalyticsPolicy = AnalyticsPolicy()) {
    internal class Used(val at: Instant, var cost: Int)
    private val used = java.util.ArrayDeque<Used>()
    // 프로젝트 한도를 여러 태스크가 공유하므로 한 태스크는 한 호출만 전송한다.
    private val calls = java.util.concurrent.Semaphore(1, true)
    inner class Reservation internal constructor(private val entry: Used) : AutoCloseable {
        private var successful = 0
        private var closed = false
        fun succeeded(cost: Int) = synchronized(this@AmplitudeBudget) {
            check(!closed && cost >= 0 && successful + cost <= entry.cost)
            successful += cost
        }
        override fun close() = synchronized(this@AmplitudeBudget) {
            if (!closed) {
                closed = true
                entry.cost = successful
                if (successful == 0) used.remove(entry)
            }
        }
    }
    @Synchronized fun reservation(cost: Int): Reservation? {
        require(cost >= 0)
        val now = clock.instant()
        while (used.isNotEmpty() && !used.first.at.isAfter(now.minusSeconds(policy.budgetWindowSeconds))) used.removeFirst()
        if (used.sumOf { it.cost.toLong() } + cost > policy.budgetCost) return null
        val entry = Used(now, cost)
        used.addLast(entry)
        return Reservation(entry)
    }
    /** 날짜 선택과 그 날짜들의 전체 비용 예약을 같은 잠금 안에서 처리한다. */
    @Synchronized fun <T> plan(build: (Int) -> Pair<Int, T>): Pair<Reservation, T> {
        val now = clock.instant()
        while (used.isNotEmpty() && !used.first.at.isAfter(now.minusSeconds(policy.budgetWindowSeconds))) used.removeFirst()
        val remaining = (policy.budgetCost - used.sumOf { it.cost }).coerceAtLeast(0)
        val (cost, value) = build(remaining)
        require(cost in 0..remaining)
        val entry = Used(now, cost); used.addLast(entry)
        return Reservation(entry) to value
    }
    /** 외부 호출 없이 비용을 소비할 때도 같은 원자적 확인을 거친다. */
    fun reserve(cost: Int): Boolean = reservation(cost)?.use { it.succeeded(cost); true } ?: false
    fun enter(deadline: Long = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(policy.requestTimeoutSeconds)) {
        try {
            if (!calls.tryAcquire((deadline - System.nanoTime()).coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS))
                throw AmplitudeFetchFailure(AnalyticsFailureReason.timeout)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error)
        }
    }
    fun leave() { calls.release() }
}

