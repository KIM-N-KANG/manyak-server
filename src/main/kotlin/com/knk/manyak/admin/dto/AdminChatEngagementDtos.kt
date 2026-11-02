package com.knk.manyak.admin.dto

import java.time.Instant
import java.time.LocalDate

data class AdminChatEngagementResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val activeMembers: Long, val monthlyActiveMembers: Long, val turnsPerActiveMember: Double?,
    val daily: List<AdminChatEngagementDay>,
)
data class AdminChatEngagementDay(
    val date: LocalDate, val activeMembers: Long, val turns: Long, val turnsPerActiveMember: Double?,
    val continuedMembers: Long, val continuationRate: Double?, val regenerations: Long,
    val regenerationRate: Double?, val stickiness: Double?,
)
