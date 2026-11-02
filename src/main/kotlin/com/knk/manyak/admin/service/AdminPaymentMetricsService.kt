package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminPaymentMetricsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AdminPaymentMetricsService(
    private val repository: AdminPaymentMetricsRepository,
    private val properties: AdminDashboardProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun metrics(request: AdminChartsRequest): AdminPaymentMetricsResponse {
        val now = clock.instant()
        val range = request.resolve(Clock.fixed(now, clock.zone))
        val start = range.from.atStartOfDay(AdminDateRange.KST).toInstant()
        val end = minOf(now, range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        val excluded = properties.excludedUserIds
        val active = repository.activeMembers(start, end, excluded)
        val totals = repository.totals(start, end, excluded)
        fun rate(numerator: Long, denominator: Long): Double? = if (denominator == 0L) null else numerator.toDouble() / denominator
        return AdminPaymentMetricsResponse(now, range.from, range.to, active, totals.payingMembers,
            rate(totals.payingMembers, active), totals.orders, totals.amountKrw, rate(totals.amountKrw, totals.payingMembers),
            totals.firstTime, totals.repeat, repository.products(start, end, excluded), totals.refundedOrders,
            rate(totals.refundedOrders, totals.orders), repository.daysToFirstPayment(start, end, excluded))
    }
}
