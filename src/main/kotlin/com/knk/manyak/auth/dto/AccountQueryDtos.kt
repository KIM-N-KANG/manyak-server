package com.knk.manyak.auth.dto

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 다른 모듈이 작성자 카드와 소유자 요약에 쓰는 회원 프로필. [com.knk.manyak.auth.service.AccountQueryService]가 돌려준다. */
data class AccountProfile(val publicId: UUID, val nickname: String, val profileImageUrl: String?)

/** 관리자 회원 조회가 public_id로 찾은 회원의 내부 id와 탈퇴 시각. */
data class AdminMemberView(val id: Long, val publicId: UUID, val deletedAt: Instant?)

/** 계정 상태 확인에 쓰는 회원의 내부 id, 공개 식별자, 상태. */
data class AccountStatusView(val id: Long, val publicId: UUID, val status: UserStatus)

/**
 * 푸시 발송 자격 판정에 쓰는 회원 값. 수신 동의 세 컬럼은 NOTIFICATION이 정하는 컬럼이다(KNK-1132, V73).
 */
data class PushRecipient(
    val id: Long,
    val publicId: UUID,
    val status: UserStatus,
    val servicePushEnabled: Boolean,
    val marketingPushAgreedAt: Instant?,
    val marketingPushNightAgreedAt: Instant?,
) {
    /**
     * [at] 시점에 광고 푸시를 보낼 수 있는지(KNK-1132, 정책 KNK-1129). 발송기(KNK-1116, 1117)가 쓴다.
     *
     * 광고 동의가 있어야 하고, 야간(21~08시 KST)이면 야간 동의까지 있어야 한다. 서비스 알림은 야간 제한이
     * 없고 필드를 그대로 보면 되므로 헬퍼를 두지 않는다.
     */
    fun canReceiveMarketingPush(at: Instant): Boolean {
        if (marketingPushAgreedAt == null) {
            return false
        }
        return !isMarketingNightHour(at) || marketingPushNightAgreedAt != null
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val NIGHT_START_HOUR = 21
        const val NIGHT_END_HOUR = 8

        /** 야간 광고 제한 구간 [21:00, 08:00) KST. 자정을 넘기므로 OR로 판정한다. */
        fun isMarketingNightHour(at: Instant): Boolean {
            val hour = at.atZone(SEOUL_ZONE).hour
            return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
        }
    }
}
