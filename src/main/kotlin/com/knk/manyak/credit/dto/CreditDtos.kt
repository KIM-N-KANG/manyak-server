package com.knk.manyak.credit.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "크레딧 잔액")
data class CreditBalanceResponse(
    @Schema(description = "현재 크레딧 잔액. 지갑이 없으면 0")
    val balance: Long,
)

@Schema(description = "출석체크 보상 결과")
data class CreditAttendanceResponse(
    @Schema(description = "이번 요청으로 보상을 지급했는지. 오늘 이미 받았으면 false(멱등)")
    val rewarded: Boolean,
    @Schema(description = "이번 요청으로 적립한 크레딧. 이미 받았으면 0")
    val amount: Long,
    @Schema(description = "지급 후 현재 잔액")
    val balance: Long,
)
