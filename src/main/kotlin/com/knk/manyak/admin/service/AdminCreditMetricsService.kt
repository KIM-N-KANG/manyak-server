package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminCreditMetricsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AdminCreditMetricsService(
    private val repository: AdminCreditMetricsRepository,
    private val properties: AdminDashboardProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun metrics(request: AdminChartsRequest): AdminCreditMetricsResponse {
        val now = clock.instant()
        val range = request.resolve(Clock.fixed(now, clock.zone))
        val start = range.from.atStartOfDay(AdminDateRange.KST).toInstant()
        val end = minOf(now, range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        val excluded = properties.excludedUserIds
        val period = repository.period(start, end, excluded)
        val granted = period.grantedByReason.sumOf { it.amount }
        val spent = period.spentByReason.sumOf { it.amount }
        return AdminCreditMetricsResponse(now, range.from, range.to, granted, spent,
            if (granted == 0L) null else spent.toDouble() / granted, period.grantedByReason, period.spentByReason,
            repository.balances(now, excluded))
    }
}
