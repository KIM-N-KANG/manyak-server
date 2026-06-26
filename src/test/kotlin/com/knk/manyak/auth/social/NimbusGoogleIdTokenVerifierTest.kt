package com.knk.manyak.auth.social

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Date

/**
 * NimbusGoogleIdTokenVerifier의 검증·추출 계약을 고정한다.
 *
 * Google JWK Set은 직접 호출하지 않는다. 대신 테스트가 생성한 RSA 키로 ID 토큰을 서명하고,
 * 같은 공개키로 검증하는 [JwtDecoder]를 주입해(verifier는 디코더만 알면 된다) 검증 규칙만 격리해서 테스트한다.
 *
 * - 유효(iss/aud 일치) → SocialUserInfo(sub/email/name/picture) 추출.
 * - issuer 두 형식(https://accounts.google.com, accounts.google.com) 모두 허용.
 * - aud가 설정 client-id에 없으면 401, 서명 불일치/만료도 401.
 */
class NimbusGoogleIdTokenVerifierTest {

    private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
    private val clientId = "client-abc.apps.googleusercontent.com"

    private fun decoder(): NimbusJwtDecoder =
        NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build()

    private fun verifier(clientIds: List<String> = listOf(clientId)): NimbusGoogleIdTokenVerifier =
        NimbusGoogleIdTokenVerifier(GoogleAuthProperties(clientIds = clientIds), decoder())

    private fun signToken(
        issuer: String = "https://accounts.google.com",
        audience: String = clientId,
        subject: String = "google-sub-1",
        email: String? = "alice@example.com",
        name: String? = "Alice",
        picture: String? = "https://example.com/a.png",
        expiresAt: Instant = Instant.now().plusSeconds(600),
    ): String {
        val now = Instant.now()
        val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(expiresAt))
        email?.let { builder.claim("email", it) }
        name?.let { builder.claim("name", it) }
        picture?.let { builder.claim("picture", it) }
        val signed = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            builder.build(),
        )
        signed.sign(RSASSASigner(rsaKey))
        return signed.serialize()
    }

    @Test
    fun `유효한 토큰이면 클레임을 SocialUserInfo로 추출한다`() {
        val info = verifier().verify(signToken())

        assertThat(info.providerUserId).isEqualTo("google-sub-1")
        assertThat(info.email).isEqualTo("alice@example.com")
        assertThat(info.name).isEqualTo("Alice")
        assertThat(info.picture).isEqualTo("https://example.com/a.png")
    }

    @Test
    fun `issuer가 accounts_google_com 형식이어도 통과한다`() {
        val info = verifier().verify(signToken(issuer = "accounts.google.com"))

        assertThat(info.providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `여러 client-id 중 하나와 aud가 일치하면 통과한다`() {
        val verifier = verifier(clientIds = listOf("other.apps.googleusercontent.com", clientId))

        val info = verifier.verify(signToken(audience = clientId))

        assertThat(info.providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `aud가 설정 client-id에 없으면 401이다`() {
        assertThatThrownBy { verifier().verify(signToken(audience = "someone-else.apps.googleusercontent.com")) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `issuer가 Google이 아니면 401이다`() {
        assertThatThrownBy { verifier().verify(signToken(issuer = "https://evil.example.com")) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `만료된 토큰이면 401이다`() {
        val expired = signToken(expiresAt = Instant.now().minusSeconds(60))

        assertThatThrownBy { verifier().verify(expired) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `다른 키로 서명한 토큰이면 401이다`() {
        val otherKey = RSAKeyGenerator(2048).keyID("other").generate()
        val now = Instant.now()
        val signed = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(otherKey.keyID).build(),
            JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(clientId)
                .subject("sub")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build(),
        )
        signed.sign(RSASSASigner(otherKey))

        assertThatThrownBy { verifier().verify(signed.serialize()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `깨진 토큰이면 401이다`() {
        assertThatThrownBy { verifier().verify("not-a-jwt") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }
}
