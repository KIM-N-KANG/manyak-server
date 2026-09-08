package com.knk.manyak.credit.dto

import com.knk.manyak.credit.entity.CreditOrderStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreditProductsResponse(val items: List<CreditProductResponse>)

data class CreditProductResponse(
    val productId: String,
    val baseCredits: Long,
    val bonusCredits: Long,
    val totalCredits: Long,
    val webPriceKrw: Long,
    val appPriceKrw: Long,
)

data class CreateCreditOrderRequest(
    @field:NotBlank
    @field:Size(max = 32)
    val productId: String,
)

data class CreateCreditOrderResponse(
    @Schema(description = "주문 공개 식별자(UUID)")
    val orderId: UUID,
    @Schema(description = "주문 ref를 포함한 그로블 결제창 URL")
    val paymentUrl: String,
)

data class CreditOrderResponse(
    val orderId: UUID,
    val productId: String,
    val status: CreditOrderStatus,
    val totalCredits: Long,
    val createdAt: Instant,
    val completedAt: Instant?,
)
