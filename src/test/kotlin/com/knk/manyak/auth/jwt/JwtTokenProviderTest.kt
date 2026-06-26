package com.knk.manyak.auth.jwt

import com.knk.manyak.auth.config.AuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * access JWT(HS256) 발급과 검증 계약을 고정한다.
 *
 * - 발급된 토큰은 같은 secret으로 만든 JwtDecoder로 검증되어야 한다(sub=publicId, iss=설정값, exp 미래).
 * - 만료된 토큰은 거부된다.
 * - 다른 secret으로 위조한 토큰은 서명 불일치로 거부된다.
 */
class JwtTokenProviderTest {

    private val secret = "test-only-jwt-secret-fixed-value-32-bytes-min"
    private val issuer = "manyak"
    private val accessTtl: Duration = Duration.ofMinutes(30)

    private fun properties(secret: String = this.secret) = AuthProperties(
        secret = secret,
        issuer = issuer,
        accessTtl = accessTtl,
        refreshTtl = Duration.ofDays(14),
    )

    private fun provider(
        props: AuthProperties = properties(),
        clock: Clock = Clock.systemUTC(),
    ) = JwtTokenProvider(props, clock)

    @Test
    fun `발급한 access 토큰은 같은 secret의 JwtDecoder로 검증되고 sub와 iss를 담는다`() {
        val provider = provider()
        val decoder: JwtDecoder = provider.jwtDecoder()
        val publicId = UUID.randomUUID()

        val token = provider.issueAccessToken(publicId)
        val jwt = decoder.decode(token)

        assertThat(jwt.subject).isEqualTo(publicId.toString())
        // iss는 URL이 아닌 임의 문자열("manyak")이라 raw claim으로 단언한다(getIssuer는 URL 변환을 시도해 실패).
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(issuer)
        assertThat(jwt.expiresAt).isNotNull()
        assertThat(jwt.issuedAt).isNotNull()
        assertThat(jwt.expiresAt).isAfter(Instant.now())
    }

    @Test
    fun `accessTtlSeconds는 설정한 access TTL의 초 단위 값이다`() {
        assertThat(provider().accessTtlSeconds()).isEqualTo(accessTtl.seconds)
    }

    @Test
    fun `만료된 access 토큰은 거부된다`() {
        // 발급 시각을 과거로 고정해 이미 만료된 토큰을 만든다.
        val past = Instant.now().minus(Duration.ofHours(2))
        val issuingProvider = provider(clock = Clock.fixed(past, ZoneOffset.UTC))
        val token = issuingProvider.issueAccessToken(UUID.randomUUID())

        // 현재 시각 기준 decoder로 검증하면 만료로 거부되어야 한다.
        val decoder = provider().jwtDecoder()

        assertThatThrownBy { decoder.decode(token) }
            .isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `다른 secret으로 위조한 토큰은 서명 불일치로 거부된다`() {
        val forger = provider(props = properties(secret = "another-secret-32-bytes-minimum-length-aaaa"))
        val forgedToken = forger.issueAccessToken(UUID.randomUUID())

        val decoder = provider().jwtDecoder()

        assertThatThrownBy { decoder.decode(forgedToken) }
            .isInstanceOf(JwtException::class.java)
    }
}
