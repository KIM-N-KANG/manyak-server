package com.knk.manyak.auth.link

import com.knk.manyak.auth.controller.FakeSocialLoginConfig
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.UserStatus
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
 * 계정 연동 API 통합 검증(KNK-739 확정 설계 — 2단계 프로토콜).
 *
 * `POST /auth/links/reauth` → `linkCode` → `POST /auth/links/{provider}` + `X-Manyak-Link-Code`.
 * 소셜 검증은 가짜 검증기([FakeSocialLoginConfig])로 대체하고, 링크 코드는 실제 Redis(내장)를 쓴다.
 *
 * 이 테스트가 지키는 완료 조건:
 * - 연동 후 그 provider로 로그인하면 **같은 계정**으로 들어온다(계정이 갈라지지 않는다).
 * - 연동 과정에서 세션이 교체되지 않는다(201 본문 없음, 토큰 미발급).
 * - 링크 코드는 성공 시에만 소비되고, 실패는 TTL 안에서 재시도할 수 있다.
 * - 연동 상태는 `GET /auth/me`의 `linkedProviders`로만 노출된다(전용 조회 엔드포인트 없음).
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

    private fun login(provider: String, idToken: String): String =
        restTestClient.post()
            .uri("/api/v1/auth/login/$provider")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$idToken"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java).returnResult().responseBody!!["accessToken"] as String

    private fun reauthExchange(accessToken: String, provider: String, idToken: String) =
        restTestClient.post()
            .uri("/api/v1/auth/links/reauth")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"provider":"$provider","idToken":"$idToken"}""")
            .exchange()

    /** 재인증까지 마치고 링크 코드를 받아 온다. */
    private fun issueLinkCode(accessToken: String, provider: String = "GOOGLE", idToken: String): String =
        reauthExchange(accessToken, provider, idToken)
            .expectStatus().isCreated
            .expectBody(Map::class.java).returnResult().responseBody!!["linkCode"] as String

    private fun linkExchange(accessToken: String, provider: String, idToken: String, linkCode: String?) =
        restTestClient.post()
            .uri("/api/v1/auth/links/$provider")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .apply { if (linkCode != null) header("X-Manyak-Link-Code", linkCode) }
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$idToken"}""")
            .exchange()

    private fun me(accessToken: String) = restTestClient.get()
        .uri("/api/v1/auth/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        .exchange()

    @Test
    fun `인증 없이 호출하면 401이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/links/reauth")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"provider":"GOOGLE","idToken":"g"}""")
            .exchange()
            .expectStatus().isUnauthorized

        restTestClient.post()
            .uri("/api/v1/auth/links/kakao")
            .header("X-Manyak-Link-Code", "whatever")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"k"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `연동 상태 전용 조회 엔드포인트는 없다`() {
        // 상태 정본은 GET /auth/me의 linkedProviders 하나다(왕복을 늘리지 않는다).
        val access = login("google", GOOGLE_SUB)

        restTestClient.get()
            .uri("/api/v1/auth/links")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $access")
            .exchange()
            .expectStatus().isNotFound
    }

    // ---- 1단계: 재인증 ----

    @Test
    fun `재인증에 성공하면 201과 링크 코드를 준다`() {
        val access = login("google", GOOGLE_SUB)

        reauthExchange(access, "GOOGLE", GOOGLE_SUB)
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.linkCode").isNotEmpty
            .jsonPath("$.expiresAt").isNotEmpty
    }

    @Test
    fun `재인증 sub가 내 연동과 다르면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        reauthExchange(access, "GOOGLE", "someone-else-google-sub")
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")
    }

    @Test
    fun `연동돼 있지 않은 provider로 재인증하면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        reauthExchange(access, "KAKAO", KAKAO_SUB)
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")
    }

    @Test
    fun `오래된 재인증 토큰이면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        reauthExchange(access, "GOOGLE", "stale:$GOOGLE_SUB")
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")
    }

    // ---- 2단계: 연동 ----

    @Test
    fun `연동에 성공하면 201이고 본문이 없으며 계정을 새로 만들지 않는다`() {
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)

        linkExchange(access, "kakao", KAKAO_SUB, code)
            .expectStatus().isCreated
            .expectBody().isEmpty

        assertThat(userRepository.count()).isEqualTo(1)
        val accounts = socialAccountRepository.findAll()
        assertThat(accounts).hasSize(2)
        assertThat(accounts.map { it.userId }.toSet()).hasSize(1)
        // 연동 행은 로그인 이력이 아니다.
        assertThat(accounts.single { it.provider == SocialProvider.KAKAO }.lastLoginAt).isNull()
    }

    @Test
    fun `연동 후 그 provider로 로그인하면 같은 계정으로 들어온다`() {
        // 이 티켓의 목적 그 자체 — 연동해 두면 계정이 갈라지지 않는다.
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isCreated

        restTestClient.post()
            .uri("/api/v1/auth/login/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$KAKAO_SUB"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isNewUser").isEqualTo(false)

        assertThat(userRepository.count()).isEqualTo(1)
        val google = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, GOOGLE_SUB)!!
        val kakao = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, KAKAO_SUB)!!
        assertThat(kakao.userId).isEqualTo(google.userId)
    }

    @Test
    fun `링크 코드 없이 연동하면 403이다`() {
        val access = login("google", GOOGLE_SUB)

        linkExchange(access, "kakao", KAKAO_SUB, null)
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")

        assertThat(socialAccountRepository.count()).isEqualTo(1)
    }

    @Test
    fun `성공에 쓴 링크 코드는 재사용할 수 없다`() {
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isCreated

        // 같은 코드로 다른 연동을 또 시도하면 이미 소비돼 403이다.
        linkExchange(access, "kakao", "another-kakao-sub", code)
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")
    }

    @Test
    fun `실패한 연동의 링크 코드는 남아 있어 재시도할 수 있다`() {
        // 재인증을 다시 요구하지 않는다(핸드오프의 "실패 시 미소비 유지"와 같은 결).
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)

        // 1차 시도: 대상 토큰이 무효라 403
        linkExchange(access, "kakao", "invalid", code)
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("SOCIAL_TOKEN_INVALID")

        // 2차 시도: 같은 코드로 정상 연동된다
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isCreated
        assertThat(socialAccountRepository.count()).isEqualTo(2)
    }

    @Test
    fun `다른 사용자의 링크 코드는 쓸 수 없다`() {
        val myAccess = login("google", GOOGLE_SUB)
        val otherAccess = login("google", "other-google-sub")
        val otherCode = issueLinkCode(otherAccess, idToken = "other-google-sub")

        linkExchange(myAccess, "kakao", KAKAO_SUB, otherCode)
            .expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.code").isEqualTo("REAUTH_FAILED")

        // 남의 코드는 소비되지 않아 원 주인이 그대로 쓸 수 있다.
        linkExchange(otherAccess, "kakao", KAKAO_SUB, otherCode).expectStatus().isCreated
    }

    @Test
    fun `다른 회원에게 연동된 소셜 계정이면 409다`() {
        val access = login("google", GOOGLE_SUB)
        login("kakao", KAKAO_SUB) // 다른 사람이 이미 그 카카오 계정으로 가입한 상태
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)

        linkExchange(access, "kakao", KAKAO_SUB, code)
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER")

        assertThat(userRepository.count()).isEqualTo(2)
        assertThat(socialAccountRepository.count()).isEqualTo(2)
    }

    @Test
    fun `이미 연동한 소셜 계정을 새 코드로 다시 연동해도 409다`() {
        // 멱등 성공으로 흘리지 않는다 — 최종 상태는 같고, 클라이언트는 409를 받아도 /auth/me로 정확히 표시한다.
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isCreated

        val secondCode = issueLinkCode(access, idToken = GOOGLE_SUB)
        linkExchange(access, "kakao", KAKAO_SUB, secondCode)
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PROVIDER_ALREADY_LINKED")

        assertThat(socialAccountRepository.count()).isEqualTo(2)
        // 실패이므로 두 번째 코드는 소비되지 않는다(다른 연동에 그대로 쓸 수 있다).
        linkExchange(access, "kakao", KAKAO_SUB, secondCode).expectStatus().isEqualTo(409)
    }

    @Test
    fun `이미 연동된 provider를 다른 계정으로 연동하면 409다`() {
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isCreated

        val secondCode = issueLinkCode(access, idToken = GOOGLE_SUB)
        linkExchange(access, "kakao", "another-kakao-sub", secondCode)
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("PROVIDER_ALREADY_LINKED")
    }

    @Test
    fun `정지 계정은 403, 삭제 계정은 401이다`() {
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)
        val user = userRepository.findAll().single()

        userRepository.save(user.apply { status = UserStatus.SUSPENDED })
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isForbidden

        userRepository.save(user.apply { status = UserStatus.DELETED })
        linkExchange(access, "kakao", KAKAO_SUB, code).expectStatus().isUnauthorized
    }

    @Test
    fun `지원하지 않는 provider나 빈 토큰이면 400이다`() {
        val access = login("google", GOOGLE_SUB)
        val code = issueLinkCode(access, idToken = GOOGLE_SUB)

        // 로그인 경로가 없는 예약 provider
        linkExchange(access, "apple", "apple-sub", code).expectStatus().isBadRequest
        // enum에 없는 값
        linkExchange(access, "facebook", "fb-sub", code).expectStatus().isBadRequest
        // 빈 idToken
        linkExchange(access, "kakao", "", code).expectStatus().isBadRequest
    }

    // ---- 연동 상태(/auth/me) ----

    @Test
    fun `me 응답의 linkedProviders는 소문자로 고정 순서다`() {
        val access = login("kakao", KAKAO_SUB)

        me(access)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.linkedProviders.length()").isEqualTo(1)
            .jsonPath("$.linkedProviders[0]").isEqualTo("kakao")

        val code = issueLinkCode(access, provider = "KAKAO", idToken = KAKAO_SUB)
        linkExchange(access, "google", GOOGLE_SUB, code).expectStatus().isCreated

        me(access)
            .expectStatus().isOk
            .expectBody()
            // 정렬은 google → kakao 고정(연동 순서와 무관)
            .jsonPath("$.linkedProviders[0]").isEqualTo("google")
            .jsonPath("$.linkedProviders[1]").isEqualTo("kakao")
    }

    private companion object {
        const val GOOGLE_SUB = "link-google-sub"
        const val KAKAO_SUB = "link-kakao-sub"
    }
}
