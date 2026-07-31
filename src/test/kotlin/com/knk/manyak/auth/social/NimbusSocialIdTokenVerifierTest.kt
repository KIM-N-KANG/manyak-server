package com.knk.manyak.auth.social

import com.knk.manyak.auth.config.AuthConfig
import com.knk.manyak.auth.entity.SocialProvider
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
 * NimbusSocialIdTokenVerifier의 검증·추출 계약을 고정한다(Google·Kakao 공용).
 *
 * provider JWK Set은 직접 호출하지 않는다. 대신 테스트가 생성한 RSA 키로 ID 토큰을 서명하고,
 * 같은 공개키로 검증하는 디코더를 주입해(verifier는 디코더만 알면 된다) 검증 규칙만 격리해서 테스트한다.
 * issuer·audience는 스펙 §4-5 표의 리터럴을 테스트가 직접 들고 있어야 본문 상수 오타를 잡을 수 있다.
 *
 * - 유효(iss/aud 일치) → SocialUserInfo(sub/email/name/picture) 추출.
 * - Google issuer 두 형식(https://accounts.google.com, accounts.google.com) 모두 허용, Kakao는 https 단일.
 * - provider가 다른 issuer는 서로 거부한다(검증기 인스턴스가 provider별로 분리됨).
 * - aud가 설정 client-id에 없으면 401, 서명 불일치/만료도 401.
 */
class NimbusSocialIdTokenVerifierTest {

    private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    private fun decoder(): NimbusJwtDecoder =
        NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build()

    private fun googleVerifier(clientIds: List<String> = listOf(GOOGLE_CLIENT_ID)): NimbusSocialIdTokenVerifier =
        NimbusSocialIdTokenVerifier(SocialProvider.GOOGLE, GOOGLE_ISSUERS, clientIds, decoder())

    private fun kakaoVerifier(clientIds: List<String> = listOf(KAKAO_APP_A)): NimbusSocialIdTokenVerifier =
        NimbusSocialIdTokenVerifier(SocialProvider.KAKAO, setOf(KAKAO_ISSUER), clientIds, decoder())

    private fun signToken(
        issuer: String = GOOGLE_ISSUER,
        audience: String = GOOGLE_CLIENT_ID,
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
        val info = googleVerifier().verify(signToken())

        assertThat(info.providerUserId).isEqualTo("google-sub-1")
        assertThat(info.email).isEqualTo("alice@example.com")
        assertThat(info.name).isEqualTo("Alice")
        assertThat(info.picture).isEqualTo("https://example.com/a.png")
    }

    @Test
    fun `issuer가 accounts_google_com 형식이어도 통과한다`() {
        val info = googleVerifier().verify(signToken(issuer = "accounts.google.com"))

        assertThat(info.providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `여러 client-id 중 하나와 aud가 일치하면 통과한다`() {
        val verifier = googleVerifier(clientIds = listOf("other.apps.googleusercontent.com", GOOGLE_CLIENT_ID))

        val info = verifier.verify(signToken(audience = GOOGLE_CLIENT_ID))

        assertThat(info.providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `aud가 설정 client-id에 없으면 401이다`() {
        assertThatThrownBy { googleVerifier().verify(signToken(audience = "someone-else.apps.googleusercontent.com")) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `issuer가 Google이 아니면 401이다`() {
        assertThatThrownBy { googleVerifier().verify(signToken(issuer = "https://evil.example.com")) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `만료된 토큰이면 401이다`() {
        val expired = signToken(expiresAt = Instant.now().minusSeconds(60))

        assertThatThrownBy { googleVerifier().verify(expired) }
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
                .issuer(GOOGLE_ISSUER)
                .audience(GOOGLE_CLIENT_ID)
                .subject("sub")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .build(),
        )
        signed.sign(RSASSASigner(otherKey))

        assertThatThrownBy { googleVerifier().verify(signed.serialize()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `깨진 토큰이면 401이다`() {
        assertThatThrownBy { googleVerifier().verify("not-a-jwt") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    // ---- Kakao(스펙 §4-5, KNK-727) ----

    @Test
    fun `카카오 issuer와 aud가 맞으면 sub를 추출한다`() {
        // 카카오는 동의항목을 요청하지 않아 email·name·picture가 오지 않는다(scope openid 단독). 셋 다 null이어도 계약이 성립해야 한다.
        val token = signToken(
            issuer = KAKAO_ISSUER,
            audience = KAKAO_APP_A,
            subject = "kakao-sub-1",
            email = null,
            name = null,
            picture = null,
        )

        val info = kakaoVerifier().verify(token)

        assertThat(info.providerUserId).isEqualTo("kakao-sub-1")
        assertThat(info.email).isNull()
        assertThat(info.name).isNull()
        assertThat(info.picture).isNull()
    }

    @Test
    fun `카카오 검증기는 Google issuer 토큰을 거부한다`() {
        assertThatThrownBy { kakaoVerifier().verify(signToken(issuer = GOOGLE_ISSUER, audience = KAKAO_APP_A)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `Google 검증기는 카카오 issuer 토큰을 거부한다`() {
        assertThatThrownBy { googleVerifier().verify(signToken(issuer = KAKAO_ISSUER)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `카카오 client-id 목록이 비면 모든 토큰을 거부한다`() {
        // fail-closed(스펙 §4-5): 키 미주입 상태에서 아무 토큰이나 통과하면 계정 탈취로 이어진다.
        val verifier = kakaoVerifier(clientIds = emptyList())

        assertThatThrownBy { verifier.verify(signToken(issuer = KAKAO_ISSUER, audience = KAKAO_APP_A)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `허용하지 않은 카카오 앱이 발급한 같은 sub 토큰은 거부한다`() {
        // 카카오 sub는 앱별(pairwise)이라 앱이 다르면 같은 sub라도 다른 사람일 수 있다(스펙 §4-5).
        // 계정 식별 키가 (provider, provider_user_id)뿐이므로, audience 검증이 앱 간 sub 충돌을 막는 유일한 방어선이다.
        val appAOnly = kakaoVerifier(clientIds = listOf(KAKAO_APP_A))
        val sharedSub = "pairwise-sub-collision"

        assertThatThrownBy {
            appAOnly.verify(signToken(issuer = KAKAO_ISSUER, audience = KAKAO_APP_B, subject = sharedSub))
        }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")

        // 대조군: 같은 sub라도 허용된 앱 A가 발급했으면 통과한다(거부 사유가 sub가 아니라 앱이라는 확인).
        val info = appAOnly.verify(signToken(issuer = KAKAO_ISSUER, audience = KAKAO_APP_A, subject = sharedSub))
        assertThat(info.providerUserId).isEqualTo(sharedSub)
    }

    // ---- azp 하드닝(KNK-739) ----

    /** aud를 여러 개 싣거나 azp를 붙인 토큰을 만든다(OIDC 다중 audience 시나리오). */
    private fun signMultiAudToken(
        audiences: List<String>,
        authorizedParty: String?,
        issuer: String = GOOGLE_ISSUER,
        subject: String = "google-sub-1",
    ): String {
        val now = Instant.now()
        val builder = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audiences)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(600)))
        authorizedParty?.let { builder.claim("azp", it) }
        val signed = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.keyID).build(),
            builder.build(),
        )
        signed.sign(RSASSASigner(rsaKey))
        return signed.serialize()
    }

    @Test
    fun `aud가 여러 개인데 azp가 없으면 401이다`() {
        // OIDC Core는 다중 audience일 때 azp 확인을 요구한다. 계정 연동은 계정 소유권을 바꾸는 경로라 이 검사가 필요하다.
        val token = signMultiAudToken(listOf(GOOGLE_CLIENT_ID, "other-app.apps.googleusercontent.com"), null)

        assertThatThrownBy { googleVerifier().verify(token) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `aud가 여러 개여도 azp가 허용 client-id면 통과한다`() {
        val token = signMultiAudToken(listOf(GOOGLE_CLIENT_ID, "other-app.apps.googleusercontent.com"), GOOGLE_CLIENT_ID)

        assertThat(googleVerifier().verify(token).providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `azp가 허용 목록에 없으면 401이다`() {
        // aud가 우리 client-id 하나여도, 그 토큰을 요청한 주체(azp)가 남의 앱이면 우리 계정에 쓰이면 안 된다.
        val token = signMultiAudToken(listOf(GOOGLE_CLIENT_ID), "attacker-app.apps.googleusercontent.com")

        assertThatThrownBy { googleVerifier().verify(token) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `azp가 없는 단일 aud 토큰은 그대로 통과한다`() {
        // 회귀 방지: 카카오는 azp를 싣지 않는다. 로그인 경로와 공유하는 코드라 기존 동작이 깨지면 안 된다.
        assertThat(kakaoVerifier().verify(signToken(issuer = KAKAO_ISSUER, audience = KAKAO_APP_A)).providerUserId)
            .isEqualTo("google-sub-1")
        assertThat(googleVerifier().verify(signToken()).providerUserId).isEqualTo("google-sub-1")
    }

    @Test
    fun `검증 파라미터는 스펙 표의 고정값을 쓴다`() {
        // 런타임 discovery 조회 없이 고정 주입하므로(스펙 §4-5), 값 오타가 곧 로그인 전면 실패다.
        assertThat(AuthConfig.KAKAO_JWK_SET_URI).isEqualTo("https://kauth.kakao.com/.well-known/jwks.json")
        assertThat(AuthConfig.KAKAO_ISSUERS).containsExactly(KAKAO_ISSUER)
        assertThat(AuthConfig.GOOGLE_JWK_SET_URI).isEqualTo("https://www.googleapis.com/oauth2/v3/certs")
        assertThat(AuthConfig.GOOGLE_ISSUERS).containsExactlyInAnyOrder(GOOGLE_ISSUER, "accounts.google.com")
    }

    private companion object {
        const val GOOGLE_ISSUER = "https://accounts.google.com"
        val GOOGLE_ISSUERS = setOf(GOOGLE_ISSUER, "accounts.google.com")
        const val GOOGLE_CLIENT_ID = "client-abc.apps.googleusercontent.com"

        const val KAKAO_ISSUER = "https://kauth.kakao.com"
        const val KAKAO_APP_A = "kakao-rest-api-key-app-a"
        const val KAKAO_APP_B = "kakao-rest-api-key-app-b"
    }
}
