package com.knk.manyak.push.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.push.entity.PushCampaignStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class AdminCampaignRequest(
    @field:NotBlank @field:Size(max = 100) val title: String,
    @field:NotBlank @field:Size(max = 300) val body: String,
    val scheduledAt: Instant? = null,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
data class AdminPushReasonRequest(@field:NotBlank @field:Size(max = 500) val reason: String)
data class AdminCampaignResponse(
    val campaignId: UUID, val kind: PushKind, val title: String, val body: String,
    val scheduledAt: Instant, val status: PushCampaignStatus, val noticeId: UUID? = null,
    val targetCount: Int?, val sentCount: Int?,
    val startedAt: Instant?, val finishedAt: Instant?, val createdAt: Instant,
    val deliveredMembers: Long = 0, val deliveredDevices: Long = 0, val failedDevices: Long = 0,
)
data class AdminCampaignPageResponse(
    val items: List<AdminCampaignResponse>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Int,
)
data class AdminCampaignTargetResponse(
    val kind: PushKind, val targetCount: Long, val nightEligibleCount: Long?, val nightNow: Boolean, val asOf: Instant,
)
data class AdminPushTemplateRequest(
    @field:NotBlank @field:Size(max = 100) val title: String,
    @field:NotBlank @field:Size(max = 300) val body: String,
    val effectiveFrom: Instant? = null, val effectiveUntil: Instant? = null,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
data class AdminPushEffectiveTextResponse(
    val title: String,
    val body: String,
    // springdoc이 is 접두를 벗겨 default로 문서화하므로 런타임과 같은 와이어 필드명을 JsonProperty로 고정한다(isOwner와 동일).
    @get:JsonProperty("isDefault")
    val isDefault: Boolean,
)
data class AdminPushTemplateOverrideResponse(
    val title: String, val body: String, val effectiveFrom: Instant, val effectiveUntil: Instant?, val active: Boolean,
)
data class AdminPushTemplateResponse(
    val templateKey: String, val effective: AdminPushEffectiveTextResponse, val overrides: List<AdminPushTemplateOverrideResponse>,
)
