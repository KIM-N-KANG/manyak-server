package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.AdminActivityHoursCount
import com.knk.manyak.admin.repository.AdminActivityHoursRepository
import com.knk.manyak.adminkit.query.AdminDateRange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class AdminActivityHoursService(
    private val repository: AdminActivityHoursRepository,
    private val properties: AdminDashboardProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun activityHours(request: AdminChartsRequest): AdminActivityHoursResponse {
        val now = clock.instant()
        val range = request.resolve(Clock.fixed(now, clock.zone))
        val from = range.from.atStartOfDay(AdminDateRange.KST).toInstant()
        val until = minOf(now, range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant().minusNanos(1000))
        val totalDays = ChronoUnit.DAYS.between(range.from, range.to).toInt() + 1
        val days = IntArray(7)
        repeat(totalDays) { offset -> days[range.from.plusDays(offset.toLong()).dayOfWeek.value - 1]++ }
        val raw = repository.counts(from, until, properties.excludedUserIds)
        fun metric(rows: List<AdminActivityHoursCount>): AdminActivityHoursMetric {
            val counts = Array(7) { LongArray(24) }
            rows.forEach { counts[it.weekday - 1][it.hour] += it.count }
            fun weekdayAverage(count: Long, weekday: Int) = if (days[weekday] == 0) null else count.toDouble() / days[weekday]
            val cells = (0..6).flatMap { day -> (0..23).map { hour ->
                AdminActivityHoursCell(day + 1, hour, counts[day][hour], weekdayAverage(counts[day][hour], day))
            } }
            val weekdays = (0..6).map { day ->
                val count = counts[day].sum()
                AdminActivityHoursWeekday(day + 1, count, weekdayAverage(count, day))
            }
            val hours = (0..23).map { hour ->
                val count = counts.sumOf { it[hour] }
                AdminActivityHoursHour(hour, count, count.toDouble() / totalDays)
            }
            val total = weekdays.sumOf { it.count }
            return AdminActivityHoursMetric(total, total.toDouble() / totalDays, cells, weekdays, hours)
        }
        return AdminActivityHoursResponse(now, range.from, range.to, totalDays,
            (0..6).map { AdminActivityHoursWeekdayDays(it + 1, days[it]) },
            AdminActivityHoursMetrics(metric(raw.signups), metric(raw.messages), metric(raw.stories), metric(raw.payments)))
    }
}
