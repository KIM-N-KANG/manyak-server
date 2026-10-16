package com.knk.manyak.global.observability.analytics

/** ACCOUNT §5-4-9 초대 제출 자격 거절 사유. */
enum class InviteRedeemFailureReason(val wireValue: String) {
    INVALID_FORMAT("invalid_format"),
    NOT_FOUND("not_found"),
    SELF_CODE("self_code"),
    ALREADY_REDEEMED("already_redeemed"),
    INVITER_WITHDRAWN("inviter_withdrawn"),
    INVITER_UNAVAILABLE("inviter_unavailable"),
    INVITER_NEWER("inviter_newer"),
}
