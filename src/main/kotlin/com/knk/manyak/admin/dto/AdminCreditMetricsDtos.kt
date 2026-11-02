package com.knk.manyak.admin.dto

import java.time.Instant
import java.time.LocalDate

data class AdminCreditMetricsResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val granted: Long, val spent: Long, val sinkRate: Double?,
    val grantedByReason: List<AdminCreditReasonAmount>, val spentByReason: List<AdminCreditReasonAmount>,
    val balances: AdminCreditBalances,
)
data class AdminCreditReasonAmount(val reason: String, val amount: Long)
data class AdminCreditBalances(
    val members: Long, val total: Long, val buckets: List<AdminCreditBalanceBucket>,
    val purchased: Long, val rewarded: Long, val legacy: Long, val expiringIn30Days: Long,
)
data class AdminCreditBalanceBucket(val bucket: String, val members: Long)
