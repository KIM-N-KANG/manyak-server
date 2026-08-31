package com.knk.manyak.global.error

import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ResponseStatusException

/**
 * 앱 수준 에러 코드를 응답 바디(`ApiErrorResponse.code`)에 실어 보내기 위한 [ResponseStatusException].
 *
 * 같은 HTTP status(예: 402)라도 사유가 다르면 바디 `code`로 구분한다(프론트 분기용). [GlobalExceptionHandler]가
 * 이 [errorCode]를 우선 사용하고, 일반 [ResponseStatusException]이면 기존대로 `status.name`을 코드로 쓴다.
 */
class CodedResponseStatusException(
    status: HttpStatusCode,
    val errorCode: String,
    reason: String,
    cause: Throwable? = null,
) : ResponseStatusException(status, reason, cause)

/** 바디 `code`로 노출하는 앱 수준 에러 코드. 프론트가 이 값으로 분기하므로 문자열을 임의로 바꾸지 않는다(와이어 계약). */
object ApiErrorCodes {
    /** 회원 크레딧 잔액 부족(402). 게스트 체험 한도와 구분한다. */
    const val INSUFFICIENT_CREDIT = "INSUFFICIENT_CREDIT"

    /** 게스트 체험 한도 소진(402). 크레딧 부족과 구분한다. */
    const val GUEST_TRIAL_LIMIT_EXCEEDED = "GUEST_TRIAL_LIMIT_EXCEEDED"

    /** 초대 코드 입력(409): 자기 자신의 코드를 제출했다. 재제출과 구분한다(스펙 §4-3-7, KNK-567). */
    const val INVITE_SELF_CODE = "INVITE_SELF_CODE"

    /** 초대 코드 입력(409): 계정당 평생 1회 자격을 이미 소진한 재제출이다(스펙 §4-3-7, KNK-567). */
    const val INVITE_ALREADY_REDEEMED = "INVITE_ALREADY_REDEEMED"

    /**
     * 초대 코드 입력(409): 초대자가 탈퇴한 회원이다(KNK-1053). 탈퇴해도 `invite_code`는 지우지 않으므로
     * 코드 자체는 매칭되고(404 아님), 사유를 구분해 안내한다.
     */
    const val INVITE_INVITER_WITHDRAWN = "INVITE_INVITER_WITHDRAWN"

    /** 초대 코드 입력(409): 초대자가 정지 상태라 지금은 쓸 수 없는 코드다(KNK-1053). 탈퇴와 구분한다. */
    const val INVITE_INVITER_UNAVAILABLE = "INVITE_INVITER_UNAVAILABLE"

    /**
     * 계정 연동(403): 이미 연동된 provider로의 재인증에 실패했다(KNK-739).
     * 토큰 무효·sub 불일치·미연동 provider·오래된 토큰을 사유 구분 없이 이 코드로 묶는다(계정 존재 여부 비노출).
     * 세션은 유효하므로 401이 아니다 — 401로 내면 클라이언트가 세션 만료로 오인해 로그아웃한다.
     */
    const val REAUTH_FAILED = "REAUTH_FAILED"

    /** 계정 연동(403): 연동하려는 소셜 ID 토큰 자체가 무효다(서명·만료·issuer·audience). 재인증 실패와 구분한다. */
    const val SOCIAL_TOKEN_INVALID = "SOCIAL_TOKEN_INVALID"

    /** 계정 연동(409): 그 소셜 계정이 이미 다른 회원에게 연동돼 있다. 합치는 것은 merge라 범위 밖이다(스펙 §4-5). */
    const val SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER = "SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER"

    /** 계정 연동(409): 내 계정에 그 provider가 이미(다른 소셜 계정으로) 연동돼 있다. 교체는 해제가 필요한데 범위 밖이다. */
    const val PROVIDER_ALREADY_LINKED = "PROVIDER_ALREADY_LINKED"

    /**
     * 계정 연동(409): 탈퇴한 계정에 연결됐던 소셜 계정이라 연동 대상이 아니다(KNK-1053).
     * 그 신원으로 **로그인**은 여전히 가능하다(재가입 경로가 받는다) — 막히는 건 다른 계정에 붙이는 것뿐이다.
     */
    const val SOCIAL_ACCOUNT_WITHDRAWN = "SOCIAL_ACCOUNT_WITHDRAWN"
}
