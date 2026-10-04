package com.knk.manyak.auth.consent

import com.knk.manyak.auth.entity.SocialProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** ID 토큰은 보관하지 않는다. 소셜 신원과 원본 디바이스는 대기 TTL 동안만 남는다. */
data class PendingSocialConsent(
    val provider: SocialProvider,
    val providerUserId: String,
    val email: String?,
    val userId: Long?,
    val deviceId: String?,
    val handoffCode: String?,
)

data class IssuedConsentToken(val code: String, val expiresAt: Instant)

@Component
class ConsentTokenStore(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    @param:Value("\${manyak.auth.consent-token.ttl:PT10M}") private val ttl: Duration,
) {
    private val random = SecureRandom()

    fun issue(pending: PendingSocialConsent): IssuedConsentToken {
        val bytes = ByteArray(32).also(random::nextBytes)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiresAt = Instant.now().plus(ttl)
        redis.opsForValue().set(key(code), mapper.writeValueAsString(pending), ttl)
        return IssuedConsentToken(code, expiresAt)
    }

    fun find(code: String): PendingSocialConsent? {
        val json = redis.opsForValue().get(key(code)) ?: return null
        // 역직렬화 예외는 원문 페이로드를 포함할 수 있으므로 전달하거나 기록하지 않는다.
        return try { mapper.readValue(json, PendingSocialConsent::class.java) } catch (_: Exception) { null }
    }

    fun consume(code: String) { redis.delete(key(code)) }

    private fun key(code: String): String = "social_consent:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8)))
}
