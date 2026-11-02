package com.knk.manyak.chat.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

data class ForkChatRequest(
    @field:Positive
    @field:Schema(description = "복사할 마지막 턴의 turns[].id", minimum = "1")
    val turnId: Long,
)
