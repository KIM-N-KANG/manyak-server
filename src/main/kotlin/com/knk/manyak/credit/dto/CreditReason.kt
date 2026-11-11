package com.knk.manyak.credit.dto

/** 크레딧 증감 사유. 적립·환불은 양수 amount, 소모·만료는 음수 amount로 기록한다(balance = SUM(amount)). */
enum class CreditReason {
    SIGNUP_REWARD,
    INVITE_REWARD,
    ATTENDANCE_REWARD,
    STORY_CREATION,
    CHAT_TURN,
    CHAT_IMAGE,
    REFUND,
    PURCHASE,
    PURCHASE_REVERSAL,
    ADMIN_GRANT,
    ADMIN_REVOKE,

    // 보상·환불 로트가 30일 유효기간을 넘겨 만료된 잔여를 회수하는 음수 행(스펙 §4-3-7 만료, B12).
    EXPIRE,
}
