package com.knk.manyak.auth.token

import java.time.Duration

/**
 * refresh 토큰을 (해시 → userId)로 보관하는 저장소 추상화.
 *
 * 토큰 원문이 아니라 해시를 키로 사용해 저장소 유출 시 원문 노출을 막는다.
 * TTL로 만료를 위임하며, 만료된 토큰은 조회되지 않는다.
 * 로그인/JWT 발급 로직은 이 추상화의 사용처(후속 작업)에서 다룬다.
 */
interface RefreshTokenStore {

    /**
     * 토큰 해시를 userId에 매핑해 저장한다. ttl 이후 만료된다.
     * ttl이 0 이하이면 즉시 만료된 것으로 간주한다(저장하지 않거나 즉시 제거).
     * 같은 tokenHash로 다시 저장하면 최신 값으로 덮어쓴다.
     */
    fun save(tokenHash: String, userId: Long, ttl: Duration)

    /** 토큰 해시에 매핑된 userId를 반환한다. 없거나 만료됐으면 null. */
    fun findUserId(tokenHash: String): Long?

    /** 토큰 해시를 제거한다. 없는 키 삭제는 무시한다. */
    fun delete(tokenHash: String)

    /** 해당 사용자의 모든 refresh 토큰을 무효화한다(전체 로그아웃 등). */
    fun deleteAllForUser(userId: Long)
}
