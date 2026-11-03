package com.knk.manyak.admin.dto

import java.time.Instant
import java.time.LocalDate

data class AdminActivityHoursResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate, val totalDays: Int,
    val weekdayDays: List<AdminActivityHoursWeekdayDays>, val metrics: AdminActivityHoursMetrics,
)
data class AdminActivityHoursWeekdayDays(val weekday: Int, val days: Int)
data class AdminActivityHoursMetrics(
    val signups: AdminActivityHoursMetric, val messages: AdminActivityHoursMetric,
    val stories: AdminActivityHoursMetric, val payments: AdminActivityHoursMetric,
)
data class AdminActivityHoursMetric(
    val total: Long, val dailyAverage: Double, val cells: List<AdminActivityHoursCell>,
    val weekdays: List<AdminActivityHoursWeekday>, val hours: List<AdminActivityHoursHour>,
)
data class AdminActivityHoursCell(val weekday: Int, val hour: Int, val count: Long, val average: Double?)
data class AdminActivityHoursWeekday(val weekday: Int, val count: Long, val average: Double?)
data class AdminActivityHoursHour(val hour: Int, val count: Long, val average: Double)
