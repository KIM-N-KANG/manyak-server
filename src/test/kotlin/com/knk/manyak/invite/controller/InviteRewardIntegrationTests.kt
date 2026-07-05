package com.knk.manyak.invite.controller

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.GoogleIdTokenVerifier
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant

/**
 * POST /api/v1/auth/login/google 의 inviteCode 초대 보상 통합 검증(KNK-393, 스펙 §4-3-7).
 *
 * Google 호출은 가짜 [GoogleIdTokenVerifier](@Primary)로 대체해 idToken을 그대로 sub로 삼는다.
 * - 최초 가입 + 유효 코드 → 초대자·피초대자 양쪽에 INVITE_REWARD(수혜자별 멱등 키).
 * - 이미 가입된 계정의 재로그인 → 초대 보상 미증가("이미 가입된 계정의 코드 제출은 무시").
 * - 미해결 코드 → 초대 보상 없이 가입 보상만.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteRewardIntegrationTests {

    @TestConfiguration
    class FakeGoogleConfig {
        @Bean
        @Primary
        fun fakeGoogleIdTokenVerifier(): GoogleIdTokenVerifier =
            GoogleIdTokenVerifier { idToken -> SocialUserInfo(providerUserId = idToken) }

        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var socialAccountRepository: SocialAccountRepository
    @Autowired private lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun seedInviter(inviteCode: String): Long =
        userRepository.save(User(nickname = "초대자", status = UserStatus.ACTIVE, inviteCode = inviteCode)).id

    private fun loginWith(idToken: String, inviteCode: String?) {
        val codeField = inviteCode?.let { ""","inviteCode":"$it"""" } ?: ""
        restTestClient.post()
            .uri("/api/v1/auth/login/google")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"idToken":"$idToken"$codeField}""")
            .exchange()
            .expectStatus().isOk
    }

    private fun userIdOf(sub: String): Long =
        socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, sub)!!.userId

    private fun inviteRewards(userId: Long) =
        creditTransactionRepository.findAll()
            .filter { it.userId == userId && it.reason == CreditReason.INVITE_REWARD }

    private fun signupRewards(userId: Long) =
        creditTransactionRepository.findAll()
            .filter { it.userId == userId && it.reason == CreditReason.SIGNUP_REWARD }

    @Test
    fun `초대 코드로 최초 가입하면 초대자와 피초대자 양쪽에 적립한다`() {
        val inviterId = seedInviter("INVCODE1")

        loginWith("invitee-sub", "INVCODE1")

        val inviteeId = userIdOf("invitee-sub")
        // 양쪽 각각 정확히 1건, 수혜자별 멱등 키를 쓴다.
        val inviterRows = inviteRewards(inviterId)
        assertThat(inviterRows).hasSize(1)
        assertThat(inviterRows.first().idempotencyKey).isEqualTo("invite:$inviterId:$inviteeId:inviter")

        val inviteeRows = inviteRewards(inviteeId)
        assertThat(inviteeRows).hasSize(1)
        assertThat(inviteeRows.first().idempotencyKey).isEqualTo("invite:$inviterId:$inviteeId:invitee")

        // 피초대자는 가입 보상도 함께 받는다. 두 지갑 모두 잔액이 쌓인다.
        assertThat(signupRewards(inviteeId)).hasSize(1)
        assertThat(creditWalletService.balanceOf(inviterId)).isGreaterThan(0)
        assertThat(creditWalletService.balanceOf(inviteeId)).isGreaterThan(creditWalletService.balanceOf(inviterId))
    }

    @Test
    fun `이미 가입된 피초대자가 같은 코드로 다시 로그인해도 초대 보상은 늘지 않는다`() {
        val inviterId = seedInviter("INVCODE2")

        loginWith("invitee2-sub", "INVCODE2")
        loginWith("invitee2-sub", "INVCODE2")

        val inviteeId = userIdOf("invitee2-sub")
        // 2회 로그인이어도 초대 보상은 신규 생성 1회만: 각 수혜자당 1건.
        assertThat(inviteRewards(inviterId)).hasSize(1)
        assertThat(inviteRewards(inviteeId)).hasSize(1)
    }

    @Test
    fun `미해결 코드로 가입하면 초대 보상 없이 가입 보상만 준다`() {
        loginWith("nocode-sub", "DOES-NOT-EXIST")

        val inviteeId = userIdOf("nocode-sub")
        assertThat(inviteRewards(inviteeId)).isEmpty()
        assertThat(signupRewards(inviteeId)).hasSize(1)
    }

    @Test
    fun `초대 보상이 유실됐어도 다음 로그인이 영속 관계로 자가 복구한다`() {
        // 회귀 방지(Codex P2): 계정 생성 트랜잭션은 커밋됐으나(초대자 관계 포함) 보상 적립 전에 실패·크래시로
        // 초대 보상이 유실된 상태를 직접 만든다(User+SocialAccount만 저장, 초대 원장 없음). 재로그인은
        // findExistingUser 히트라 생성 경로를 안 타지만, 영속된 inviterUserId로 매 로그인 멱등 적립이 복구해야 한다.
        val now = Instant.now()
        val inviterId = seedInviter("HEAL5555")
        val invitee = userRepository.save(
            User(nickname = "보상유실피초대자", status = UserStatus.ACTIVE, inviterUserId = inviterId),
        )
        socialAccountRepository.save(
            SocialAccount(
                userId = invitee.id,
                provider = SocialProvider.GOOGLE,
                providerUserId = "heal-invitee-sub",
                email = "invitee@example.com",
                connectedAt = now,
                lastLoginAt = now,
            ),
        )
        // 사전 상태: 초대 보상이 유실돼 양쪽 원장이 비어 있다.
        assertThat(inviteRewards(inviterId)).isEmpty()
        assertThat(inviteRewards(invitee.id)).isEmpty()

        // 코드 없이 재로그인해도(기존 사용자) 영속 관계로 초대 보상을 복구한다.
        loginWith("heal-invitee-sub", null)

        assertThat(inviteRewards(inviterId)).hasSize(1)
        assertThat(inviteRewards(invitee.id)).hasSize(1)
        assertThat(creditWalletService.balanceOf(inviterId)).isGreaterThan(0)
        // 새 사용자를 만들지 않았다(기존 계정 재사용).
        assertThat(userRepository.count()).isEqualTo(2)
    }
}
