package com.knk.manyak.admin.service

import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminModerationMetricsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class AdminModerationMetricsService(
    private val repository: AdminModerationMetricsRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun metrics(request: AdminChartsRequest): AdminModerationMetricsResponse {
        val now = clock.instant()
        val range = request.resolve(Clock.fixed(now, clock.zone))
        val start = range.from.atStartOfDay(AdminDateRange.KST).toInstant()
        val end = minOf(now, range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        val raw = repository.daily(start, end)
        val days = ChronoUnit.DAYS.between(range.from, range.to).toInt() + 1
        val daily = (0 until days).map { offset ->
            val date = range.from.plusDays(offset.toLong())
            raw[date] ?: AdminModerationMetricsDay(date, 0, 0, 0, 0)
        }
        val approved = daily.sumOf { it.approved }
        val rejected = daily.sumOf { it.rejected }
        val decided = approved + rejected
        val backlog = repository.backlog()
        return AdminModerationMetricsResponse(now, range.from, range.to, decided, approved, rejected,
            if (decided == 0L) null else rejected.toDouble() / decided, daily.sumOf { it.failed }, backlog.held, backlog.pending,
            daily, repository.rejectionRules(start, end))
    }
}
