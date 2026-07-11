package com.knk.manyak.invite.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "내 초대 코드·보상 진행")
data class InviteResponse(
    @field:Schema(
        description = "내 고유 초대 코드(혼동 문자를 제외한 대문자+숫자 8자). 다른 회원이 이 코드를 " +
            "POST /users/me/invite/redeem에 제출하면 양쪽에 크레딧이 적립된다(KNK-567).",
        example = "AB3XK9QZ",
    )
    val inviteCode: String,
    @field:Schema(
        description = "이번 KST 월에 요청자가 수령한 초대 보상 횟수. 초대 보상 판정과 동일하게 적립 시점 월 귀속 " +
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

@Schema(description = "초대 코드 입력 요청(스펙 §4-3-7, KNK-567)")
data class InviteRedeemRequest(
    @field:NotBlank
    @field:Schema(
        description = "제출할 초대 코드. 서버가 trim·대문자 정규화 후 비교하므로 소문자·앞뒤 공백은 허용된다.",
        example = "AB3XK9QZ",
    )
    val code: String,
)

@Schema(description = "초대 코드 입력 결과")
data class InviteRedeemResponse(
    @field:Schema(
        description = "이번 입력으로 제출자에게 적립된 크레딧. 제출자가 월 상한에 도달한 상태면 0이다(요청은 성공).",
        example = "500",
    )
    val amount: Long,
    @field:Schema(description = "적립 후 제출자의 크레딧 잔액(만료분 제외).", example = "1000")
    val balance: Long,
)
