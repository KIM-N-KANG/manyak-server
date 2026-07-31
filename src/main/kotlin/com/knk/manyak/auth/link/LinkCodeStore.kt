package com.knk.manyak.auth.link

import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * 계정 연동용 일회용 링크 코드 저장소(KNK-739).
 *
 * 재인증([AccountLinkService.reauthenticate])이 발급하고 연동이 소비한다. 저장은 Redis `link_auth:{codeHash}`
 * 한 키이며 값은 소유자 `userId`다. [LoginHandoffService][com.knk.manyak.auth.handoff.LoginHandoffService]의
 * 보관 패턴을 그대로 따른다 — TTL 만료가 곧 소멸이라 청소 배치가 없고, **키는 코드 원문이 아니라 SHA-256 해시**라
 * 저장소가 유출돼도 코드 원문은 드러나지 않는다.
 *
 * TTL은 짧다(기본 5분). 재인증 직후 소셜 팝업 한 번을 더 도는 시간만 살아 있으면 되고, 길수록 탈취된 코드의
 * 유효 창이 넓어진다.
 *
 * 코드 원문은 발급 응답에서만 노출되며 URL이 아니라 헤더로만 다시 받는다(요청 URI는 구조화 로그·Sentry에 남는다).
 */
@Component
class LinkCodeStore(
    private val redisTemplate: StringRedisTemplate,
    @param:Value("\${manyak.auth.link.code-ttl:PT5M}")
    private val ttl: Duration,
) {

    private val secureRandom = SecureRandom()

    /** 일회용 코드를 발급해 소유자와 함께 보관한다. */
    fun issue(userId: Long): LinkCodeResponse {
        val code = generateCode()
        val expiresAt = Instant.now().plus(ttl)
        redisTemplate.opsForValue().set(keyFor(code), userId.toString(), ttl)
        return LinkCodeResponse(linkCode = code, expiresAt = expiresAt)
    }

    /** 코드의 소유자 userId. 없음·만료·이미 소비됨은 모두 null이다(호출부에서 사유를 구분하지 않는다). */
    fun findUserId(code: String): Long? =
        redisTemplate.opsForValue().get(keyFor(code))?.toLongOrNull()

    /**
     * 코드를 소비한다(1회용). **연동에 성공했을 때만 호출한다** — 403·409로 실패한 시도까지 소비하면
     * 사용자가 실수 한 번에 재인증을 처음부터 다시 해야 한다(핸드오프의 "실패 시 미소비 유지"와 같은 결).
     */
    fun consume(code: String) {
        redisTemplate.delete(keyFor(code))
    }

    /** 256bit 무작위 코드를 base64url로 만든다(핸드오프 코드와 동일 규격). */
    private fun generateCode(): String {
        val bytes = ByteArray(CODE_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun keyFor(code: String): String = KEY_PREFIX + hash(code)

    /** 코드 원문의 SHA-256 해시를 base64url로 반환한다. Redis 키로만 쓴다(원문은 저장하지 않는다). */
    private fun hash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val KEY_PREFIX = "link_auth:"
        const val CODE_BYTES = 32 // 256bit
    }
}
