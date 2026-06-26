package com.knk.manyak.auth.token

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * RefreshTokenStore의 Redis 구현(family 기반).
 *
 * 키 구조(모두 TTL 적용):
 * - `rt:fam:{familyId}` → `"userId:currentTokenHash"` — family 레코드(소유자 + 현재 유효 토큰).
 * - `rt:tok:{tokenHash}` → familyId — 발급된 모든 토큰(과거 포함)의 family 매핑(재사용 탐지용).
 * - `rt:user:{userId}`   → familyId SET — 사용자의 모든 family 인덱스(전체 폐기용).
 *
 * 회전/폐기는 Lua 스크립트로 **원자 실행**한다. Redis는 스크립트를 단일 원자 단위로 돌리므로,
 * 동시 회전이 같은 현재 토큰을 둘 다 통과시키거나, logout과 회전이 경합해 세션이 살아남는 일이 없다.
 * (ElastiCache 비클러스터 모드를 전제로, 스크립트 내부에서 키를 동적으로 만든다.)
 *
 * Lettuce는 지연 연결이므로 이 빈이 등록돼도 Redis 미가용 상태에서 컨텍스트 로딩 자체는 실패하지 않는다.
 */
@Repository
class RedisRefreshTokenStore(
    private val redisTemplate: StringRedisTemplate,
) : RefreshTokenStore {

    override fun createFamily(tokenHash: String, userId: Long, ttl: Duration): String {
        val familyId = UUID.randomUUID().toString()
        if (ttl.isZero || ttl.isNegative) return familyId // 즉시 만료: 저장하지 않는다.
        redisTemplate.opsForValue().set(familyKey(familyId), "$userId:$tokenHash", ttl)
        redisTemplate.opsForValue().set(tokenKey(tokenHash), familyId, ttl)
        val userKey = userIndexKey(userId)
        redisTemplate.opsForSet().add(userKey, familyId)
        redisTemplate.expire(userKey, ttl)
        return familyId
    }

    override fun rotate(presentedTokenHash: String, newTokenHash: String, ttl: Duration): RotateResult {
        if (ttl.isZero || ttl.isNegative) return RotateResult.Invalid // 회전은 양수 TTL 전제(실제 refreshTtl).
        val result = redisTemplate.execute(
            ROTATE_SCRIPT,
            emptyList(),
            presentedTokenHash,
            newTokenHash,
            ttl.seconds.toString(),
        ) ?: return RotateResult.Invalid
        return parseRotate(result)
    }

    override fun revokeFamilyByToken(tokenHash: String) {
        redisTemplate.execute(REVOKE_BY_TOKEN_SCRIPT, emptyList(), tokenHash)
    }

    override fun revokeAllForUser(userId: Long) {
        redisTemplate.execute(REVOKE_ALL_SCRIPT, emptyList(), userId.toString())
    }

    private fun parseRotate(result: String): RotateResult = when {
        result.startsWith(ROTATED_PREFIX) -> RotateResult.Rotated(result.removePrefix(ROTATED_PREFIX).toLong())
        result.startsWith(REUSE_PREFIX) -> RotateResult.ReuseDetected(result.removePrefix(REUSE_PREFIX).toLong())
        else -> RotateResult.Invalid
    }

    private fun familyKey(familyId: String) = "$FAMILY_PREFIX$familyId"
    private fun tokenKey(tokenHash: String) = "$TOKEN_PREFIX$tokenHash"
    private fun userIndexKey(userId: Long) = "$USER_PREFIX$userId"

    companion object {
        private const val FAMILY_PREFIX = "rt:fam:"
        private const val TOKEN_PREFIX = "rt:tok:"
        private const val USER_PREFIX = "rt:user:"

        private const val ROTATED_PREFIX = "ROTATED:"
        private const val REUSE_PREFIX = "REUSE:"

        /**
         * 회전을 원자적으로 수행한다. ARGV: [1]=제시 토큰 해시, [2]=새 토큰 해시, [3]=TTL(초).
         * 반환: `INVALID` | `ROTATED:{userId}` | `REUSE:{userId}`.
         * userId는 정수, 토큰 해시는 base64url(콜론 없음)이라 family 값은 첫 ':'로 안전하게 나뉜다.
         */
        private val ROTATE_SCRIPT = DefaultRedisScript<String>().apply {
            setResultType(String::class.java)
            setScriptText(
                """
                local familyId = redis.call('GET', 'rt:tok:' .. ARGV[1])
                if not familyId then return 'INVALID' end
                local fam = redis.call('GET', 'rt:fam:' .. familyId)
                if not fam then return 'INVALID' end
                local sep = string.find(fam, ':')
                local userId = string.sub(fam, 1, sep - 1)
                local currentHash = string.sub(fam, sep + 1)
                if currentHash ~= ARGV[1] then
                    redis.call('DEL', 'rt:fam:' .. familyId)
                    redis.call('SREM', 'rt:user:' .. userId, familyId)
                    return 'REUSE:' .. userId
                end
                local ttl = tonumber(ARGV[3])
                redis.call('SET', 'rt:fam:' .. familyId, userId .. ':' .. ARGV[2], 'EX', ttl)
                redis.call('SET', 'rt:tok:' .. ARGV[2], familyId, 'EX', ttl)
                redis.call('EXPIRE', 'rt:user:' .. userId, ttl)
                return 'ROTATED:' .. userId
                """.trimIndent(),
            )
        }

        /** 제시 토큰이 속한 family를 통째로 폐기한다(멱등). ARGV: [1]=토큰 해시. */
        private val REVOKE_BY_TOKEN_SCRIPT = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                local familyId = redis.call('GET', 'rt:tok:' .. ARGV[1])
                if not familyId then return 0 end
                local fam = redis.call('GET', 'rt:fam:' .. familyId)
                redis.call('DEL', 'rt:fam:' .. familyId)
                if fam then
                    local sep = string.find(fam, ':')
                    local userId = string.sub(fam, 1, sep - 1)
                    redis.call('SREM', 'rt:user:' .. userId, familyId)
                end
                return 1
                """.trimIndent(),
            )
        }

        /** 사용자의 모든 family를 폐기한다. ARGV: [1]=userId. */
        private val REVOKE_ALL_SCRIPT = DefaultRedisScript<Long>().apply {
            setResultType(Long::class.java)
            setScriptText(
                """
                local userKey = 'rt:user:' .. ARGV[1]
                local fams = redis.call('SMEMBERS', userKey)
                for _, fid in ipairs(fams) do
                    redis.call('DEL', 'rt:fam:' .. fid)
                end
                redis.call('DEL', userKey)
                return #fams
                """.trimIndent(),
            )
        }
    }
}
