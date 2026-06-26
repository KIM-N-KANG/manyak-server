package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.GoogleIdTokenVerifier
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.web.server.ResponseStatusException

/**
 * POST /api/v1/auth/login/google 통합 검증.
 *
 * Google 호출은 가짜 [GoogleIdTokenVerifier](@Primary)로 대체해 외부 IO 없이 검증한다.
 * - 유효 토큰("valid-...") → 200 + TokenResponse, find-or-create 부작용.
 * - verifier가 401을 던지는 토큰("invalid") → 401.
 * - 본문 누락/빈 idToken → 400.
 *
 * refresh 저장은 Redis 인프라 없이 검증하기 위해 InMemoryRefreshTokenStore(@Primary)로 대체한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoogleLoginIntegrationTests {

    @TestConfiguration
    class FakeGoogleConfig {
        // "invalid"는 401, 그 외(유효 토큰)는 토큰을 sub로 삼아 고정 사용자 정보를 돌려준다.
        @Bean
        @Primary
        fun fakeGoogleIdTokenVerifier(): GoogleIdTokenVerifier =
            GoogleIdTokenVerifier { idToken ->
                if (idToken == "invalid") {
                    throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.")
                }
                SocialUserInfo(
                    providerUserId = idToken,
                    email = "user@example.com",
                    name = "테스터",
                    picture = "https://example.com/p.png",
                )
            }

        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialAccountRepository: SocialAccountRepository

    @BeforeEach
    fun setUp() {
        socialAccountRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @Test
    fun `유효한 idToken으로 로그인하면 200과 토큰을 반환하고 사용자를 생성한다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"new-google-sub"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.accessToken").isNotEmpty
            .jsonPath("$.refreshToken").isNotEmpty
            .jsonPath("$.tokenType").isEqualTo("Bearer")
            .jsonPath("$.expiresIn").value<Int> { assertThat(it).isGreaterThan(0) }

        val social = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "new-google-sub")
        assertThat(social).isNotNull
        assertThat(userRepository.findById(social!!.userId)).isPresent
    }

    @Test
    fun `같은 idToken으로 두 번 로그인해도 사용자는 하나만 생성된다`() {
        repeat(2) {
            restTestClient.post()
                .uri("/api/v1/auth/login/google")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"idToken":"repeat-sub"}""")
                .exchange()
                .expectStatus().isOk
        }

        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(socialAccountRepository.count()).isEqualTo(1)
    }

    @Test
    fun `verifier가 거부하는 토큰이면 401이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"invalid"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `만료된 Authorization Bearer 헤더가 붙어도 로그인은 동작한다`() {
        // 클라이언트가 로그아웃 후에도 stale access를 자동 첨부하는 시나리오.
        // 로그인 경로는 bearerTokenResolver에서 토큰을 무시하므로 401이 나면 안 된다.
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .header("Authorization", "Bearer stale.access.token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"with-stale-header"}""")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `idToken이 비어 있으면 400이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":""}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `본문이 없으면 400이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange()
            .expectStatus().isBadRequest
    }
}
