package com.knk.manyak.auth.token

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

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
        redisTemplate.opsForValue().set(tokenKey, userId.toString(), ttl)
        // 사용자 인덱스에 해시를 추가하고, 토큰 만료보다 일찍 사라지지 않도록 인덱스 TTL을 함께 연장한다.
        val userKey = userIndexKey(userId)
        redisTemplate.opsForSet().add(userKey, tokenHash)
        redisTemplate.expire(userKey, ttl)
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
