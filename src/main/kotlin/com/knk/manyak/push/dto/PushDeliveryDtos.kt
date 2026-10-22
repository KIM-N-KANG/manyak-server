package com.knk.manyak.push.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class PushDeliveryType { STORY_COMPLETED, STORY_MODERATION_COMPLETED, ATTENDANCE_REMINDER, PROMOTION, NOTICE }
enum class PushDeliveryOutcome { DELIVERED, UNDELIVERED, DISCARDED }

data class PushDeliveryReportRequest(
    @field:NotBlank @field:Size(max = 255) val messageId: String,
    val type: PushDeliveryType,
    val kind: PushKind,
    val recipientId: UUID,
    val campaignId: UUID? = null,
    val outcome: PushDeliveryOutcome,
    @field:NotBlank @field:Size(max = 40) val reason: String,
    @field:Min(0) val deliveredDevices: Int,
    @field:Min(0) val failedDevices: Int,
    val completedAt: Instant,
)

data class CampaignDeliveryStats(val deliveredMembers: Long, val deliveredDevices: Long, val failedDevices: Long)
data class PushDeliveryStatsItem(val date: LocalDate, val type: PushDeliveryType, val kind: PushKind,
    val requested: Long, val deliveredMembers: Long, val deliveredDevices: Long, val failedDevices: Long)
data class PushDeliveryStatsResponse(val from: LocalDate, val to: LocalDate, val items: List<PushDeliveryStatsItem>)
