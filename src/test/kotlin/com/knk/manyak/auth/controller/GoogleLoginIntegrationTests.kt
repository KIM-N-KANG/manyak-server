package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.GoogleIdTokenVerifier
import com.knk.manyak.auth.social.RandomNicknameGenerator
import com.knk.manyak.auth.social.SocialUserInfo
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.support.DatabaseCleaner
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
import java.time.Instant

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
                when {
                    idToken == "invalid" ->
                        throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.")
                    else -> SocialUserInfo(
                        providerUserId = idToken,
                        email = "user@example.com",
                        name = GOOGLE_DISPLAY_NAME,
                        picture = "https://example.com/p.png",
                    )
                }
            }

        companion object {
            const val GOOGLE_DISPLAY_NAME = "테스터"
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

    @Autowired
    private lateinit var creditWalletService: CreditWalletService

    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
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
    fun `신규 로그인은 Google 이름이 아닌 랜덤 닉네임을 발급한다`() {
        // 스펙 §4-5: 실명 노출을 피하기 위해 Google `name`을 쓰지 않고 랜덤 닉네임을 발급한다.
        // 발급값은 VARCHAR(50) 이내여야 하고, Google display name과 달라야 한다.
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"new-random-nick"}""")
            .exchange()
            .expectStatus().isOk

        val social = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "new-random-nick")
        assertThat(social).isNotNull
        val user = userRepository.findById(social!!.userId).orElseThrow()
        assertThat(user.nickname).isNotBlank()
        assertThat(user.nickname.length).isLessThanOrEqualTo(RandomNicknameGenerator.MAX_NICKNAME_LENGTH)
        assertThat(user.nickname).isNotEqualTo(FakeGoogleConfig.GOOGLE_DISPLAY_NAME)
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
    fun `신규 로그인은 가입 보상 크레딧을 자동 적립한다`() {
        // 스펙 §4-3-7 가입 보상: 회원 최초 생성 시 서버가 자동 적립(별도 API 없음).
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"reward-new-sub"}""")
            .exchange()
            .expectStatus().isOk

        val social = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "reward-new-sub")
        val userId = social!!.userId
        assertThat(creditWalletService.balanceOf(userId)).isGreaterThan(0)
        val rewards = creditTransactionRepository.findAll()
            .filter { it.userId == userId && it.reason == CreditReason.SIGNUP_REWARD }
        assertThat(rewards).hasSize(1)
        assertThat(rewards.first().idempotencyKey).isEqualTo("signup:$userId")
    }

    @Test
    fun `같은 사용자가 다시 로그인해도 가입 보상은 한 번만 적립된다`() {
        // 매 로그인마다 보상을 시도하지만 멱등 키(signup:{userId})가 중복을 막아 실제 적립은 1회다.
        repeat(2) {
            restTestClient.post()
                .uri("/api/v1/auth/login/google")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"idToken":"reward-repeat-sub"}""")
                .exchange()
                .expectStatus().isOk
        }

        val social = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "reward-repeat-sub")
        val userId = social!!.userId
        val rewards = creditTransactionRepository.findAll()
            .filter { it.userId == userId && it.reason == CreditReason.SIGNUP_REWARD }
        assertThat(rewards).hasSize(1)
    }

    @Test
    fun `보상 없이 이미 존재하는 회원이 로그인하면 가입 보상을 자가 복구한다`() {
        // 회귀 방지(Codex P2): 계정 생성 트랜잭션은 커밋됐으나 보상 적립 전에 실패·크래시로 보상이 유실된 상태를
        // 직접 만든 뒤(User+SocialAccount만 저장, 원장 없음), 같은 Google sub로 로그인한다.
        // 다음 로그인은 findExistingUser 히트라 생성 경로를 안 타지만, 매 로그인 멱등 적립이 유실을 복구해야 한다.
        val now = Instant.now()
        val user = userRepository.save(User(nickname = "보상유실회원", status = UserStatus.ACTIVE))
        socialAccountRepository.save(
            SocialAccount(
                userId = user.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = "self-heal-sub",
                email = "user@example.com",
                connectedAt = now,
                lastLoginAt = now,
            ),
        )
        // 사전 상태: 원장·잔액이 비어 있다(보상 유실).
        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(0)

        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"self-heal-sub"}""")
            .exchange()
            .expectStatus().isOk

        // 기존 사용자 로그인이지만 이제 가입 보상이 정확히 1건 적립된다(잔액>0).
        assertThat(creditWalletService.balanceOf(user.id)).isGreaterThan(0)
        val rewards = creditTransactionRepository.findAll()
            .filter { it.userId == user.id && it.reason == CreditReason.SIGNUP_REWARD }
        assertThat(rewards).hasSize(1)
        assertThat(rewards.first().idempotencyKey).isEqualTo("signup:${user.id}")
        // 새 사용자를 만들지 않았다(기존 계정 재사용).
        assertThat(userRepository.count()).isEqualTo(1)
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
