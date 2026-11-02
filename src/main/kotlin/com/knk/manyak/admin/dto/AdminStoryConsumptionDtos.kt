package com.knk.manyak.admin.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AdminStoryConsumptionRequest : AdminChartsRequest() {
    @field:Min(1) @field:Max(50) var limit: Int = 20
}

data class AdminStoryConsumptionResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val publicStories: Long, val officialStories: Long, val playedStories: Long, val playedRate: Double?,
    val otherMemberChats: Long, val topConcentration: Double?, val ranking: List<AdminStoryConsumptionRank>,
    val creators: AdminStoryCreators,
)
data class AdminStoryConsumptionRank(
    val storyId: UUID, val title: String, val official: Boolean, val deleted: Boolean,
    val chats: Long, val players: Long, val turns: Long,
)
data class AdminStoryCreators(
    val activeMembers: Long, val members: Long, val rate: Double?,
    val previousMembers: Long, val retained: Long, val retentionRate: Double?,
)
