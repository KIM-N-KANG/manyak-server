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
}
