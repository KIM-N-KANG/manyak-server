package com.knk.manyak.auth.controller

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.RandomNicknameGenerator
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * POST /api/v1/auth/login/kakao 통합 검증(스펙 §4-5, KNK-727).
 *
 * 흐름·응답 계약은 Google과 동일하므로(검증 파라미터만 다름) 공통 경로의 상세 검증은
 * [GoogleLoginIntegrationTests]가 맡고, 여기서는 카카오 고유 관심사를 본다.
 * - 카카오 연동(social_accounts.provider = KAKAO)으로 find-or-create 되는지
 * - 같은 sub라도 provider가 다르면 별개 계정인지(계정 통합 미도입 — §4-5 결정 기록)
 * - 프로필·가입 보상이 provider와 무관하게 같은 규칙인지
 *
 * 카카오 호출은 가짜 검증기([FakeSocialLoginConfig])로 대체해 외부 IO 없이 검증한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeSocialLoginConfig::class)
class KakaoLoginIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var socialAccountRepository: SocialAccountRepository

    @Autowired
    private lateinit var creditTransactionRepository: CreditTransactionRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun login(idToken: String) = restTestClient.post()
        .uri("/api/v1/auth/login/kakao")
        .contentType(MediaType.APPLICATION_JSON)
        .body("""{"idToken":"$idToken"}""")
        .exchange()

    @Test
    fun `유효한 idToken으로 로그인하면 200과 토큰을 반환하고 카카오 연동 사용자를 생성한다`() {
        login("new-kakao-sub")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.accessToken").isNotEmpty
            .jsonPath("$.refreshToken").isNotEmpty
            .jsonPath("$.tokenType").isEqualTo("Bearer")
            .jsonPath("$.expiresIn").value<Int> { assertThat(it).isGreaterThan(0) }

        val social = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "new-kakao-sub")
        assertThat(social).isNotNull
        assertThat(social!!.provider).isEqualTo(SocialProvider.KAKAO)
        assertThat(userRepository.findById(social.userId)).isPresent
    }

    @Test
    fun `카카오 신규 가입도 소셜 표시명이 아닌 랜덤 닉네임을 발급한다`() {
        // 프로필 발급은 provider와 무관하게 동일하다(스펙 §4-5 — 랜덤 닉네임 + 명사 매핑 프리셋).
        login("kakao-nick-sub").expectStatus().isOk

        val social = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-nick-sub")
        val user = userRepository.findById(social!!.userId).orElseThrow()
        assertThat(user.nickname).isNotBlank()
        assertThat(user.nickname.length).isLessThanOrEqualTo(RandomNicknameGenerator.MAX_NICKNAME_LENGTH)
        assertThat(user.nickname).isNotEqualTo(FakeSocialLoginConfig.SOCIAL_DISPLAY_NAME)
    }

    @Test
    fun `카카오 신규 가입 로그인은 isNewUser true, 재로그인은 false다`() {
        login("kakao-new-flag-sub")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isNewUser").isEqualTo(true)

        login("kakao-new-flag-sub")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isNewUser").isEqualTo(false)
    }

    @Test
    fun `같은 sub라도 provider가 다르면 별개 계정이 된다`() {
        // 계정 통합은 도입하지 않는다(스펙 §4-5 결정 기록). 식별 키가 (provider, provider_user_id)이므로
        // 구글로 가입한 뒤 같은 문자열 sub로 카카오 로그인하면 계정이 하나 더 생겨야 한다.
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"shared-sub-value"}""")
            .exchange()
            .expectStatus().isOk

        login("shared-sub-value")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.isNewUser").isEqualTo(true)

        val google = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "shared-sub-value")
        val kakao = socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.KAKAO, "shared-sub-value")
        assertThat(google!!.userId).isNotEqualTo(kakao!!.userId)
        assertThat(userRepository.count()).isEqualTo(2)
    }

    @Test
    fun `같은 idToken으로 두 번 로그인해도 사용자는 하나만 생성되고 가입 보상도 한 번만 적립된다`() {
        repeat(2) { login("kakao-repeat-sub").expectStatus().isOk }

        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(socialAccountRepository.count()).isEqualTo(1)
        val userId = socialAccountRepository
            .findByProviderAndProviderUserId(SocialProvider.KAKAO, "kakao-repeat-sub")!!.userId
        val rewards = creditTransactionRepository.findAll()
            .filter { it.userId == userId && it.reason == CreditReason.SIGNUP_REWARD }
        assertThat(rewards).hasSize(1)
        assertThat(rewards.first().idempotencyKey).isEqualTo("signup:$userId")
    }

    @Test
    fun `verifier가 거부하는 토큰이면 401이다`() {
        login("invalid").expectStatus().isUnauthorized
    }

    @Test
    fun `idToken이 비어 있으면 400이다`() {
        login("").expectStatus().isBadRequest
    }

    @Test
    fun `본문이 없으면 400이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/login/kakao")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange()
            .expectStatus().isBadRequest
    }
}
