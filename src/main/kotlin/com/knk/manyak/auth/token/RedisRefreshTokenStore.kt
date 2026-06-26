package com.knk.manyak.auth.token

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * RefreshTokenStore의 Redis 구현.
 *
 * 키 구조:
 * - `refresh:{tokenHash}` → userId (TTL 적용, 만료는 Redis에 위임)
 * - `user:{userId}:refresh` → 해당 사용자 토큰 해시들의 SET 인덱스(전체 무효화용)
 *
 * Lettuce는 지연 연결이므로, 이 빈이 등록되어도 Redis 미가용 상태에서 컨텍스트 로딩 자체는 실패하지 않는다.
 * 실제 명령 시점에 Redis가 필요하다.
 */
@Repository
class RedisRefreshTokenStore(
    private val redisTemplate: StringRedisTemplate,
) : RefreshTokenStore {

    override fun save(tokenHash: String, userId: Long, ttl: Duration) {
        if (ttl.isZero || ttl.isNegative) {
            // 즉시 만료: 저장하지 않고 기존 값까지 정리한다.
            delete(tokenHash)
            return
        }
        val tokenKey = tokenKey(tokenHash)
        // 같은 해시가 다른 사용자에게 매핑돼 있었다면 이전 사용자 인덱스의 stale 엔트리를 먼저 제거한다.
        // (안 그러면 이전 사용자의 deleteAllForUser가 새 주인의 토큰을 폐기할 수 있다.)
        val previousUserId = redisTemplate.opsForValue().get(tokenKey)?.toLongOrNull()
        if (previousUserId != null && previousUserId != userId) {
            redisTemplate.opsForSet().remove(userIndexKey(previousUserId), tokenHash)
        }
        redisTemplate.opsForValue().set(tokenKey, userId.toString(), ttl)
        val userKey = userIndexKey(userId)
        redisTemplate.opsForSet().add(userKey, tokenHash)
        // 인덱스 만료는 연장만 한다. 더 짧은 TTL의 토큰이 추가돼도 기존 인덱스 수명을 단축하지 않는다.
        // (인덱스가 토큰보다 먼저 만료되면 deleteAllForUser가 아직 유효한 토큰을 못 지운다.)
        // 비교는 밀리초로 한다 — 초 단위로 자르면 sub-second 차이가 같게 잘려 연장이 누락될 수 있다.
        val currentTtlMillis = redisTemplate.getExpire(userKey, TimeUnit.MILLISECONDS)
        if (currentTtlMillis < ttl.toMillis()) {
            redisTemplate.expire(userKey, ttl)
        }
    }

    override fun findUserId(tokenHash: String): Long? =
        redisTemplate.opsForValue().get(tokenKey(tokenHash))?.toLongOrNull()

    override fun delete(tokenHash: String) {
        val userId = findUserId(tokenHash)
        redisTemplate.delete(tokenKey(tokenHash))
        if (userId != null) {
            redisTemplate.opsForSet().remove(userIndexKey(userId), tokenHash)
        }
    }

    override fun deleteAllForUser(userId: Long) {
        val userKey = userIndexKey(userId)
        val tokenHashes = redisTemplate.opsForSet().members(userKey).orEmpty()
        if (tokenHashes.isNotEmpty()) {
            redisTemplate.delete(tokenHashes.map { tokenKey(it) })
        }
        redisTemplate.delete(userKey)
    }

    private fun tokenKey(tokenHash: String) = "$TOKEN_KEY_PREFIX$tokenHash"

    private fun userIndexKey(userId: Long) = "$USER_INDEX_PREFIX$userId$USER_INDEX_SUFFIX"

    companion object {
        private const val TOKEN_KEY_PREFIX = "refresh:"
        private const val USER_INDEX_PREFIX = "user:"
        private const val USER_INDEX_SUFFIX = ":refresh"
    }
}
