package com.knk.manyak.chat.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class AdminChatModelPromotionRequest(
    @field:Min(0) @field:Max(10000) val turnCost: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
data class AdminChatModelPromotionDeleteRequest(@field:NotBlank @field:Size(max = 500) val reason: String)
enum class ChatModelPromotionStatus { SCHEDULED, ACTIVE, ENDED }
data class AdminChatModelPromotionResponse(
    val turnCost: Long, val startsAt: Instant, val endsAt: Instant, val status: ChatModelPromotionStatus,
)
data class AdminChatModelResponse(
    val code: String, val name: String, val turnCost: Long,
    @get:JsonProperty("isActive") val isActive: Boolean,
    @get:JsonProperty("isDefault") val isDefault: Boolean,
    val promotion: AdminChatModelPromotionResponse?,
)
data class AdminChatModelsResponse(val items: List<AdminChatModelResponse>)
