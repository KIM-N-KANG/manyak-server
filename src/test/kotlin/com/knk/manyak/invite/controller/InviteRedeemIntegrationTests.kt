package com.knk.manyak.invite.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
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

/**
 * POST /api/v1/users/me/invite/redeem 통합 검증(KNK-565, 스펙 §4-3-7 KNK-567 개편).
 *
 * - 성공: 초대자·제출자 양쪽 INVITE_REWARD 적립, 초대 관계(inviter_user_id) 저장, {amount, balance} 응답.
 * - 정규화: 제출 code는 trim·대문자 변환 후 비교한다.
 * - 오류 계약: 빈 값·형식 위반 400, 매칭 없음 404, 자기 코드 409 INVITE_SELF_CODE,
 *   재제출(평생 1회 소진) 409 INVITE_ALREADY_REDEEMED, 정지 계정 403, 미인증 401.
 * - 초대자 월 상한(10회) 도달 시 초대자 적립만 건너뛰고 제출자는 적립하며 응답은 200.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteRedeemIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var creditTransactionRepository: CreditTransactionRepository
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String, inviteCode: String? = null, inviterUserId: Long? = null): User =
        userRepository.save(
            User(nickname = nickname, status = UserStatus.ACTIVE, inviteCode = inviteCode, inviterUserId = inviterUserId),
        )

    private fun tokenOf(user: User): String = jwtTokenProvider.issueAccessToken(user.publicId)

    private fun redeem(token: String?, code: String) =
        restTestClient.post()
            .uri("/api/v1/users/me/invite/redeem")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"code":"$code"}""")
            .exchange()

    private fun inviteRewards(userId: Long) =
        creditTransactionRepository.findAll()
            .filter { it.userId == userId && it.reason == CreditReason.INVITE_REWARD }

    @Test
    fun `토큰 없이 제출하면 401이다`() {
        redeem(null, "REDEEM77").expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 코드를 제출하면 양쪽에 적립하고 초대 관계를 저장한다`() {
        val inviter = saveUser("초대자", inviteCode = "REDEEM77")
        val redeemer = saveUser("제출자")

        redeem(tokenOf(redeemer), "REDEEM77")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.amount").isEqualTo(500)
            .jsonPath("$.balance").isEqualTo(500)

        // 초대자·제출자 각 1건, 수혜자별 멱등 키(invite:{초대자}:{피초대자}:{수혜자}).
        val inviterRows = inviteRewards(inviter.id)
        assertThat(inviterRows).hasSize(1)
        assertThat(inviterRows.first().idempotencyKey).isEqualTo("invite:${inviter.id}:${redeemer.id}:${inviter.id}")
        val redeemerRows = inviteRewards(redeemer.id)
        assertThat(redeemerRows).hasSize(1)
        assertThat(redeemerRows.first().idempotencyKey).isEqualTo("invite:${inviter.id}:${redeemer.id}:${redeemer.id}")

        // 초대 관계는 redeem 트랜잭션에서 저장된다(평생 1회 판정 근거).
        assertThat(userRepository.findById(redeemer.id).orElseThrow().inviterUserId).isEqualTo(inviter.id)
    }

    @Test
    fun `제출 코드는 trim과 대문자 정규화 후 비교한다`() {
        val inviter = saveUser("초대자", inviteCode = "TRIMUP99")
        val redeemer = saveUser("제출자")

        redeem(tokenOf(redeemer), "  trimup99  ").expectStatus().isOk

        assertThat(inviteRewards(inviter.id)).hasSize(1)
        assertThat(inviteRewards(redeemer.id)).hasSize(1)
    }

    @Test
    fun `빈 코드는 400이다`() {
        val redeemer = saveUser("제출자")

        redeem(tokenOf(redeemer), "   ").expectStatus().isBadRequest
    }

    @Test
    fun `형식에 맞지 않는 코드는 400이다`() {
        val redeemer = saveUser("제출자")

        // 길이 위반(8자 아님). 조회 전에 형식으로 거른다(스펙 §4-3-7 초대 코드 입력 규칙).
        redeem(tokenOf(redeemer), "ABC").expectStatus().isBadRequest
    }

    @Test
    fun `매칭되는 코드가 없으면 404다`() {
        val redeemer = saveUser("제출자")

        redeem(tokenOf(redeemer), "ZZZZ9999").expectStatus().isNotFound

        assertThat(inviteRewards(redeemer.id)).isEmpty()
        assertThat(userRepository.findById(redeemer.id).orElseThrow().inviterUserId).isNull()
    }

    @Test
    fun `자기 코드를 제출하면 409 INVITE_SELF_CODE다`() {
        val redeemer = saveUser("제출자", inviteCode = "MYSELF77")

        redeem(tokenOf(redeemer), "MYSELF77")
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVITE_SELF_CODE")

        assertThat(inviteRewards(redeemer.id)).isEmpty()
    }

    @Test
    fun `이미 입력을 마친 계정의 재제출은 409 INVITE_ALREADY_REDEEMED다`() {
        saveUser("첫초대자", inviteCode = "FIRST777")
        val second = saveUser("둘째초대자", inviteCode = "SECOND77")
        val redeemer = saveUser("제출자")
        val token = tokenOf(redeemer)

        redeem(token, "FIRST777").expectStatus().isOk

        // 다른 초대자의 코드라도 계정당 평생 1회를 소진했으므로 거부한다.
        redeem(token, "SECOND77")
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVITE_ALREADY_REDEEMED")

        assertThat(inviteRewards(second.id)).isEmpty()
        assertThat(inviteRewards(redeemer.id)).hasSize(1)
    }

    @Test
    fun `초대 관계가 이미 저장된 계정도 재제출로 보고 409다`() {
        val legacyInviter = saveUser("기존초대자")
        saveUser("새초대자", inviteCode = "NEWCODE7")
        val redeemer = saveUser("제출자", inviterUserId = legacyInviter.id)

        redeem(tokenOf(redeemer), "NEWCODE7")
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVITE_ALREADY_REDEEMED")
    }

    @Test
    fun `초대자가 월 상한에 도달했으면 제출자만 적립하고 200이다`() {
        val inviter = saveUser("상한초대자", inviteCode = "CAPPED77")
        val redeemer = saveUser("제출자")
        // 이번 달 초대 보상 10건(상한)을 미리 적립해 상한 도달 상태를 만든다.
        repeat(10) { i ->
            creditWalletService.reward(inviter.id, 500, CreditReason.INVITE_REWARD, "seed:${inviter.id}:$i")
        }

        redeem(tokenOf(redeemer), "CAPPED77")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.amount").isEqualTo(500)

        // 초대자는 상한 스킵(10건 그대로), 제출자는 적립. 관계는 저장된다.
        assertThat(inviteRewards(inviter.id)).hasSize(10)
        assertThat(inviteRewards(redeemer.id)).hasSize(1)
        assertThat(userRepository.findById(redeemer.id).orElseThrow().inviterUserId).isEqualTo(inviter.id)
    }

    @Test
    fun `적립 성공은 초대자의 GET invite 진행 카운트에 반영된다`() {
        val inviter = saveUser("진행초대자", inviteCode = "PROGRES7")
        val redeemer = saveUser("제출자")

        redeem(tokenOf(redeemer), "PROGRES7").expectStatus().isOk

        // 진행 표시는 상한 판정과 같은 창(INVITE_REWARD 원장의 이번 KST 월 집계)을 재사용한다(KNK-513).
        restTestClient.get()
            .uri("/api/v1/users/me/invite")
            .header("Authorization", "Bearer ${tokenOf(inviter)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.monthlyRewardCount").isEqualTo(1)
            .jsonPath("$.monthlyRewardLimit").isEqualTo(10)
    }

    @Test
    fun `정지된 계정의 제출은 403이다`() {
        saveUser("초대자", inviteCode = "SUSPEND7")
        val suspended = userRepository.save(User(nickname = "정지제출자", status = UserStatus.SUSPENDED))

        redeem(tokenOf(suspended), "SUSPEND7").expectStatus().isForbidden

        assertThat(inviteRewards(suspended.id)).isEmpty()
    }
}
