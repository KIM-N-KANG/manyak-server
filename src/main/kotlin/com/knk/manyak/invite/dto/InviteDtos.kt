package com.knk.manyak.invite.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "내 초대 코드·공유 링크")
data class InviteResponse(
    @field:Schema(description = "내 고유 초대 코드. 피초대자가 로그인 시 함께 보내면 양쪽에 크레딧이 적립된다.", example = "Ab3Xk9Qz")
    val inviteCode: String,
    @field:Schema(description = "공유용 초대 링크(코드 포함).", example = "https://manyak.app/invite/Ab3Xk9Qz")
    val inviteUrl: String,
)
