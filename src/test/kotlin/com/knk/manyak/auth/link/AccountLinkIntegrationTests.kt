package com.knk.manyak.auth.link

import com.knk.manyak.auth.controller.FakeSocialLoginConfig
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 계정 연동 API 통합 검증(KNK-738/739 — 스펙 §4-5 후속).
 *
 * `GET·POST /api/v1/auth/links`. 소셜 검증은 가짜 검증기([FakeSocialLoginConfig])로 대체해 외부 IO 없이 본다.
 * 가짜 검증기는 토큰 문자열을 그대로 `sub`로 쓰고, `stale:` 접두는 오래된 발급 시각을, `invalid`는 401을 낸다.
 *
 * 이 테스트가 지키는 완료 조건:
 * - 마이페이지가 연동 상태를 provider별로 확인할 수 있다.
 * - 연동 후 그 provider로 로그인하면 **같은 계정**으로 들어온다(계정이 갈라지지 않는다).
 * - 연동 과정에서 세션이 교체되지 않는다(응답에 토큰 없음).
 * - 다른 계정에 연동된 소셜 계정은 거부된다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeSocialLoginConfig::class)
class AccountLinkIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialAccountRepository: SocialAccountRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    /** 소셜 로그인으로 계정을 만들고 access 토큰을 얻는다. */
    private fun login(provider: String, idToken: String): String =
        restTestClient.post()
            .uri("/api/v1/auth/login/$provider")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$idToken"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["accessToken"] as String

    private fun linkRequest(
        accessToken: String,
        provider: String,
        idToken: String,
        reauthProvider: String = "GOOGLE",
        reauthIdToken: String,
    ) = restTestClient.post()
        .uri("/api/v1/auth/links")
        .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            """
            {
              "provider": "$provider",
              "idToken": "$idToken",
              "reauth": { "provider": "$reauthProvider", "idToken": "$reauthIdToken" }
            }
            """.trimIndent(),
        )
        .exchange()

    private fun getLinks(accessToken: String) = restTestClient.get()
        .uri("/api/v1/auth/links")
        .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        .exchange()

    @Test
    fun `인증 없이 호출하면 401이다`() {
        restTestClient.get().uri("/api/v1/auth/links").exchange().expectStatus().isUnauthorized

        restTestClient.post()
            .uri("/api/v1/auth/links")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"provider":"KAKAO","idToken":"k","reauth":{"provider":"GOOGLE","idToken":"g"}}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `연동 상태는 로그인 가능한 provider를 모두 내려준다`() {
        val access = login("google", GOOGLE_SUB)

        getLinks(access)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.links.length()").isEqualTo(2)
            .jsonPath("$.links[0].provider").isEqualTo("GOOGLE")
            .jsonPath("$.links[0].linked").isEqualTo(true)
            .jsonPath("$.links[0].connectedAt").isNotEmpty
            .jsonPath("$.links[1].provider").isEqualTo("KAKAO")
            .jsonPath("$.links[1].linked").isEqualTo(false)
            .jsonPath("$.links[1].connectedAt").doesNotExist()
    }

    @Test
    fun `연동에 성공하면 최신 상태를 돌려주고 계정을 새로 만들지 않는다`() {
        val access = login("google", GOOGLE_SUB)

        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = GOOGLE_SUB)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.links[1].provider").isEqualTo("KAKAO")
            .jsonPath("$.links[1].linked").isEqualTo(true)
            // 세션은 그대로다 — 연동 응답은 토큰을 싣지 않는다(refresh family 생성 0회).
            .jsonPath("$.accessToken").doesNotExist()
            .jsonPath("$.refreshToken").doesNotExist()

        assertThat(userRepository.count()).isEqualTo(1)
        val accounts = socialAccountRepository.findAll()
        assertThat(accounts).hasSize(2)
        assertThat(accounts.map { it.userId }.toSet()).hasSize(1)
        // 연동 행은 로그인 이력이 아니므로 lastLoginAt이 비어 있다.
        assertThat(accounts.single { it.provider == SocialProvider.KAKAO }.lastLoginAt).isNull()
    }

    @Test
    fun `연동 후 그 provider로 로그인하면 같은 계정으로 들어온다`() {
        // 이 티켓의 목적 그 자체 — 연동해 두면 계정이 갈라지지 않는다.
        val access = login("google", GOOGLE_SUB)
        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = GOOGLE_SUB)
            .expectStatus().isOk

        restTestClient.post()
            .uri("/api/v1/auth/login/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$KAKAO_SUB"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            // 기존 계정 재사용이므로 신규 가입이 아니다.
            .jsonPath("$.isNewUser").isEqualTo(false)

        assertThat(userRepository.count()).isEqualTo(1)
        val google = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, GOOGLE_SUB)!!
        val kakao = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)!!
        assertThat(kakao.userId).isEqualTo(google.userId)
        // 연동 뒤 실제 로그인이 발생했으므로 이제는 마지막 로그인 시각이 찍힌다.
        assertThat(kakao.lastLoginAt).isNotNull()
    }

    @Test
    fun `같은 소셜 계정을 다시 연동해도 200이고 행이 늘지 않는다`() {
        val access = login("google", GOOGLE_SUB)
        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = GOOGLE_SUB).expectStatus().isOk

        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = GOOGLE_SUB)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.links[1].linked").isEqualTo(true)

        assertThat(socialAccountRepository.count()).isEqualTo(2)
    }

    @Test
    fun `다른 회원에게 연동된 소셜 계정이면 409다`() {
        val access = login("google", GOOGLE_SUB)
        login("kakao", KAKAO_SUB) // 다른 사람이 이미 그 카카오 계정으로 가입한 상태

        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = GOOGLE_SUB)
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER")

        assertThat(userRepository.count()).isEqualTo(2)
        assertThat(socialAccountRepository.count()).isEqualTo(2)
    }

    @Test
    fun `이미 연동된 provider를 다른 계정으로 연동하면 409다`() {
        val access = login("google", GOOGLE_SUB)
        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = GOOGLE_SUB).expectStatus().isOk

        linkRequest(access, provider = "KAKAO", idToken = "another-kakao-sub", reauthIdToken = GOOGLE_SUB)
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PROVIDER_ALREADY_LINKED")
    }

    @Test
    fun `재인증 sub가 내 연동과 다르면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = "someone-else-google-sub")
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")

        assertThat(socialAccountRepository.count()).isEqualTo(1)
    }

    @Test
    fun `연동돼 있지 않은 provider로 재인증하면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        linkRequest(
            access,
            provider = "KAKAO",
            idToken = KAKAO_SUB,
            reauthProvider = "KAKAO",
            reauthIdToken = KAKAO_SUB,
        )
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")
    }

    @Test
    fun `오래된 재인증 토큰이면 403이다`() {
        // 최초 로그인 때 받아 보관해 둔 ID 토큰 재제출 차단(공용 기기 시나리오).
        val access = login("google", GOOGLE_SUB)

        linkRequest(access, provider = "KAKAO", idToken = KAKAO_SUB, reauthIdToken = "stale:$GOOGLE_SUB")
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")
    }

    @Test
    fun `연동 대상 토큰이 무효면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        linkRequest(access, provider = "KAKAO", idToken = "invalid", reauthIdToken = GOOGLE_SUB)
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("SOCIAL_TOKEN_INVALID")
    }

    @Test
    fun `지원하지 않는 provider나 빈 토큰이면 400이다`() {
        val access = login("google", GOOGLE_SUB)

        // 로그인 경로가 없는 예약 provider(APPLE)
        linkRequest(access, provider = "APPLE", idToken = "apple-sub", reauthIdToken = GOOGLE_SUB)
            .expectStatus().isBadRequest

        // enum에 없는 값
        linkRequest(access, provider = "FACEBOOK", idToken = "fb-sub", reauthIdToken = GOOGLE_SUB)
            .expectStatus().isBadRequest

        // 빈 idToken
        linkRequest(access, provider = "KAKAO", idToken = "", reauthIdToken = GOOGLE_SUB)
            .expectStatus().isBadRequest
    }

    private companion object {
        const val GOOGLE_SUB = "link-google-sub"
        const val KAKAO_SUB = "link-kakao-sub"
    }
}
