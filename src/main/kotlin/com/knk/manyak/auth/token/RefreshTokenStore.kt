package com.knk.manyak.auth.token

import java.time.Duration

/**
 * refresh 토큰을 "family(세션/기기)" 단위로 보관하는 저장소 추상화.
 *
 * 한 번의 로그인이 하나의 family를 만든다. 회전(rotate)은 같은 family의 **현재 토큰**을
 * 새 토큰으로 교체하고, 로그아웃·재사용 탐지는 **family 전체**를 폐기한다.
 *
 * 토큰 키는 SHA-256 해시다. 회전 시 새 refresh 원문은 재사용 유예 시간 동안만 별도로 보관한다.
 * TTL로 만료를 위임하며, 만료된 항목은 조회/회전되지 않는다.
 *
 * 보안 동기(KNK-288, Codex PR #72 P2):
 * - **logout vs 동시 refresh 경합**: 같은 토큰을 logout과 refresh가 동시에 처리해도 family를
 *   통째로 폐기하므로, 회전으로 새 토큰이 나가도 그 family가 사라져 세션이 살아남지 못한다.
 * - **재사용 탐지**: 유예 조건 밖에서 과거 토큰이 다시 제시되면 family 전체를 폐기한다.
 */
interface RefreshTokenStore {

    /**
     * 로그인: 새 세션 family를 만들고 첫 refresh 토큰 해시를 현재 토큰으로 등록한다.
     * 생성된 familyId를 반환한다. ttl 이후 family와 토큰 매핑이 만료된다.
     * ttl이 0 이하이면 아무것도 저장하지 않고 새 familyId만 반환한다(즉시 만료).
     */
    fun createFamily(tokenHash: String, userId: Long, ttl: Duration): String

    /**
     * 회전: [presentedTokenHash]를 원자적으로 검증·소비하고, 같은 family의 현재 토큰이면 [newTokenHash]로 교체한다.
     *
     * - 현재 토큰 → [RotateResult.Rotated] (현재 토큰을 newTokenHash로 교체, family TTL을 ttl로 갱신)
     * - 과거 토큰의 유예 키가 살아 있고 보관된 해시가 family 현재 해시와 같으면 [RotateResult.GraceReplayed]. family와 TTL 유지
     * - 그 외 살아 있는 family의 과거 토큰 → family 전체를 폐기하고 [RotateResult.ReuseDetected]
     * - 알 수 없음/만료/이미 폐기된 family → [RotateResult.Invalid]
     *
     * [reuseGrace]가 0 이하면 유예 키를 만들지 않는다. [newRefreshToken]은 유예 키에만 보관한다.
     * 검증과 교체 및 유예 판정은 단일 원자 연산이어야 한다(동시 회전이 같은 현재 토큰을 둘 다 통과시키는 레이스 차단).
     */
    fun rotate(
        presentedTokenHash: String,
        newTokenHash: String,
        newRefreshToken: String,
        ttl: Duration,
        reuseGrace: Duration,
    ): RotateResult

    /**
     * 제시된 토큰이 속한 family 전체를 폐기한다(단일 기기 로그아웃).
     * 현재 토큰이든 과거 토큰이든 같은 family면 모두 무효화된다. 멱등 — 알 수 없는 토큰은 무시한다.
     */
    fun revokeFamilyByToken(tokenHash: String)

    /** 사용자의 모든 family를 폐기한다(전체 로그아웃·계정 정지/삭제 등). */
    fun revokeAllForUser(userId: Long)
}

/** [RefreshTokenStore.rotate]의 결과. */
sealed interface RotateResult {
    /** 회전 성공. [newTokenHash]가 현재 토큰으로 등록됐다. */
    data class Rotated(val userId: Long) : RotateResult

    /** 유예 응답. family와 TTL을 유지하며 직전 회전에서 발급한 같은 원문을 반환한다. */
    data class GraceReplayed(val userId: Long, val refreshToken: String) : RotateResult

    /** 이미 회전된 과거 토큰의 재사용을 탐지해 family 전체를 폐기했다(탈취 정황). */
    data class ReuseDetected(val userId: Long) : RotateResult

    /** 알 수 없거나 만료됐거나 이미 폐기된 토큰. */
    data object Invalid : RotateResult
}
