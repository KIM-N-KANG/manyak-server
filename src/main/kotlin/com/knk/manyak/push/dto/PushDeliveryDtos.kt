package com.knk.manyak.push.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class PushDeliveryType { STORY_COMPLETED, STORY_MODERATION_COMPLETED, STORY_TAKEN_DOWN, ATTENDANCE_REMINDER, CREDIT_EXPIRY_REMINDER, PROMOTION, NOTICE }
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

data class PushCampaignReach(val campaignId: java.util.UUID, val kind: PushKind, val title: String, val noticeId: java.util.UUID?,
    val startedAt: java.time.Instant?, val deepLinkIncluded: Boolean?, val deliveredMembers: Long)
data class PushActivityReach(val type: PushDeliveryType, val date: java.time.LocalDate, val deliveredMembers: Long)

/** 다른 도메인에 제공하는 도달 시각. 원문과 기기 식별자는 노출하지 않는다. */
data class PushMemberDelivery(val userId: Long, val type: PushDeliveryType, val completedAt: Instant, val campaignId: UUID? = null)
