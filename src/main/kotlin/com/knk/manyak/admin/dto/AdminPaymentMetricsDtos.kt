package com.knk.manyak.admin.dto

import java.time.Instant
import java.time.LocalDate

data class AdminPaymentMetricsResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val activeMembers: Long, val payingMembers: Long, val conversionRate: Double?,
    val orders: Long, val amountKrw: Long, val arppu: Double?,
    val firstTime: AdminPaymentTotals, val repeat: AdminPaymentTotals,
    val products: List<AdminPaymentProductMetrics>, val refundedOrders: Long, val refundRate: Double?,
    val daysToFirstPayment: List<AdminFirstPaymentBucket>,
)
data class AdminPaymentTotals(val orders: Long, val amountKrw: Long)
data class AdminPaymentProductMetrics(val productId: String, val orders: Long, val amountKrw: Long, val creditAmount: Long)
data class AdminFirstPaymentBucket(val bucket: String, val members: Long)
