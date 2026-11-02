package com.knk.manyak.admin.dto

import java.time.Instant
import java.time.LocalDate

data class AdminModerationMetricsResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val decided: Long, val approved: Long, val rejected: Long, val rejectionRate: Double?,
    val failed: Long, val held: Long, val pending: Long,
    val daily: List<AdminModerationMetricsDay>, val rejectionRules: List<AdminModerationRejectionRule>,
)
data class AdminModerationMetricsDay(
    val date: LocalDate, val submitted: Long, val approved: Long, val rejected: Long, val failed: Long,
)
data class AdminModerationRejectionRule(val rule: String, val type: String, val submissions: Long)
