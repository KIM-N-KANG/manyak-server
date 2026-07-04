package com.knk.manyak.credit.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "크레딧 잔액")
data class CreditBalanceResponse(
    @Schema(description = "현재 크레딧 잔액. 지갑이 없으면 0")
    val balance: Long,
)
