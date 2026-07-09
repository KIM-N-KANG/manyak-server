package com.knk.manyak.invite.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "내 초대 코드·공유 링크")
data class InviteResponse(
    @field:Schema(description = "내 고유 초대 코드. 피초대자가 로그인 시 함께 보내면 양쪽에 크레딧이 적립된다.", example = "Ab3Xk9Qz")
    val inviteCode: String,
    @field:Schema(description = "공유용 초대 링크(코드 포함).", example = "https://manyak.app/invite/Ab3Xk9Qz")
    val inviteUrl: String,
    @field:Schema(
        description = "이번 KST 월에 요청자가 수령한 초대 보상 횟수. 초대 보상 판정과 동일하게 피초대자 가입 월 귀속 " +
            "INVITE_REWARD 원장 집계이며, 상한에 도달하면 monthlyRewardLimit과 같아진다.",
        example = "3",
    )
    val monthlyRewardCount: Long,
    @field:Schema(
        description = "초대 보상 월 상한(현재 10). 정책 수치라 클라이언트 하드코딩을 피하도록 응답에 함께 싣는다.",
        example = "10",
    )
    val monthlyRewardLimit: Long,
)
