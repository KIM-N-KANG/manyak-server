package com.knk.manyak.global.security

import java.util.UUID

/**
 * 보안 필터와 `@CurrentUserId` 해석이 토큰 주체의 계정을 계정 모듈에 묻는 입구(KNK-2068). global이 정의하고 auth가 구현한다
 * (BE-061: 아래 계층이 값을 돌려받아야 하면 아래 계층이 인터페이스를 정의한다). 응답 상태와 본문은 global 필터가 정한다.
 */
interface AccountAccessPort {

    /** 회원 공개 식별자를 내부 PK로 바꾼다. 회원이 없으면 null이며 계정 상태는 보지 않는다. */
    fun userIdOf(publicId: UUID): Long?

    /**
     * 인증이 확정된 요청을 판정한다. 탈퇴를 먼저 보고, 회원이 없으면 동의 게이트 없이 통과시키며,
     * 동의 게이트가 켜져 있으면 허용 목록(메서드와 정확한 경로) 밖의 요청에 현행 필수 동의를 요구한다(ACCOUNT 5-1-3).
     */
    fun check(publicId: UUID, method: String, path: String): AccountAccess
}

/** [AccountAccessPort.check]의 판정 결과. */
enum class AccountAccess {
    ALLOWED,
    DELETED,
    CONSENT_REQUIRED,
}
