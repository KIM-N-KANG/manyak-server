package com.knk.manyak.admin

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

class AdminTokenTests {
    private val now = Instant.parse("2026-10-21T00:00:00Z")
    private val properties = AuthProperties("test-only-secret-of-at-least-32-bytes", "manyak", Duration.ofMinutes(30), Duration.ofDays(30))
    private fun provider(at: Instant = now) = JwtTokenProvider(properties, Clock.fixed(at, ZoneOffset.UTC))

    @Test fun `앱과 관리자 토큰은 상호 거절하며 관리자 토큰은 2시간 뒤 만료된다`() {
        val issuer = provider()
        val id = UUID.randomUUID()
        val admin = issuer.issueAdminToken(id)
        val decoded = issuer.adminJwtDecoder().decode(admin)
        assertThat(decoded.audience).containsExactly("manyak-admin")
        assertThat(decoded.subject).isEqualTo(id.toString())
        assertThat(decoded.expiresAt).isEqualTo(now.plusSeconds(7200))
        assertThat(decoded.headers["alg"]).isEqualTo("HS256")
        assertThatThrownBy { issuer.jwtDecoder().decode(admin) }.isInstanceOf(JwtException::class.java)
        assertThatThrownBy { issuer.adminJwtDecoder().decode(issuer.issueAccessToken(id)) }.isInstanceOf(JwtException::class.java)
        assertThatThrownBy { provider(now.plusSeconds(7261)).adminJwtDecoder().decode(admin) }.isInstanceOf(JwtException::class.java)
        assertThat(provider(now.plusSeconds(7259)).adminJwtDecoder().decode(admin).subject).isEqualTo(id.toString())
    }

    @Test fun `선택 인증 필터는 관리자 토큰을 익명으로 처리한다`() {
        val filter = com.knk.manyak.global.security.OptionalJwtAuthenticationFilter(provider().jwtDecoder(),
            org.springframework.security.web.util.matcher.RequestMatcher { true })
        for (admin in listOf(true, false)) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
            val token = if (admin) provider().issueAdminToken(UUID.randomUUID()) else provider().issueAccessToken(UUID.randomUUID())
            val request = org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/credits/policies").apply {
                addHeader("Authorization", "Bearer $token")
            }
            try {
                filter.doFilter(request, org.springframework.mock.web.MockHttpServletResponse(), jakarta.servlet.FilterChain { _, _ ->
                    val authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication
                    if (admin) assertThat(authentication).isNull() else assertThat(authentication).isNotNull()
                })
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
        }
    }

    @Test fun `빈 대상 클레임도 앱 토큰으로 인정하지 않는다`() {
        for (aud in listOf("[]", "null")) {
            val payload = """{"iss":"manyak","sub":"${UUID.randomUUID()}","aud":$aud,"iat":${now.epochSecond},"exp":${now.plusSeconds(7200).epochSecond}}"""
            val token = com.nimbusds.jose.JWSObject(JWSHeader(JWSAlgorithm.HS256), com.nimbusds.jose.Payload(payload))
                .apply { sign(MACSigner(properties.secret)) }.serialize()
            assertThatThrownBy { provider().jwtDecoder().decode(token) }.isInstanceOf(JwtException::class.java)
        }
    }

    private fun signed(audience: List<String>?, subject: String = UUID.randomUUID().toString(), issuer: String = "manyak", expiry: Instant? = now.plusSeconds(7200)): String {
        val builder = JWTClaimsSet.Builder().issuer(issuer).subject(subject).issueTime(Date.from(now))
        audience?.let(builder::audience)
        expiry?.let { builder.expirationTime(Date.from(it)) }
        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build()).apply { sign(MACSigner(properties.secret)) }.serialize()
    }

    @Test fun `대상 있는 토큰은 앱에서 모두 거절하고 관리자는 표식과 UUID와 만료를 요구한다`() {
        for (aud in listOf(listOf("other"), listOf("manyak-admin", "other"))) {
            assertThatThrownBy { provider().jwtDecoder().decode(signed(aud)) }.isInstanceOf(JwtException::class.java)
        }
        for (token in listOf(signed(listOf("other")), signed(null), signed(listOf("manyak-admin"), subject = "bad"), signed(listOf("manyak-admin"), subject = "1-1-1-1-1"),
            signed(listOf("manyak-admin"), issuer = "bad"), signed(listOf("manyak-admin"), expiry = null))) {
            assertThatThrownBy { provider().adminJwtDecoder().decode(token) }.isInstanceOf(JwtException::class.java)
        }
        val multi = signed(listOf("other", "manyak-admin"))
        assertThat(provider().adminJwtDecoder().decode(multi).audience).contains("manyak-admin")
        val forged = multi.dropLast(5) + "xxxxx"
        assertThatThrownBy { provider().adminJwtDecoder().decode(forged) }.isInstanceOf(JwtException::class.java)
    }
}
