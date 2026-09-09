package com.knk.manyak.credit.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

class GooglePlayPurchaseRequest(
    @field:NotBlank @field:Size(max = 32) val productId: String,
    @field:NotBlank @field:Size(max = 4096) val purchaseToken: String,
)

data class GooglePlayPurchaseResponse(val orderId: UUID, val balance: Long)
