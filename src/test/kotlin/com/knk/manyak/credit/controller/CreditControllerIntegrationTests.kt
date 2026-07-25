package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * GET /api/v1/users/me/credits 통합 검증(KNK-87).
 * - 인증 필수: 토큰 없음·사용자 없음 → 401.
 * - 지갑 없으면 balance 0, 적립 후에는 그 잔액을 반환한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditControllerIntegrationTests {


    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(): User =
        userRepository.save(User(nickname = "크레딧유저", status = UserStatus.ACTIVE))

    @Test
    fun `토큰 없이 크레딧 조회하면 401이다`() {
        restTestClient.get().uri("/api/v1/users/me/credits").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `지갑이 없으면 잔액은 0이다`() {
        val user = saveUser()

        restTestClient.get()
            .uri("/api/v1/users/me/credits")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.balance").isEqualTo(0)
    }

    @Test
    fun `적립 후에는 그 잔액을 반환한다`() {
        val user = saveUser()
        creditWalletService.reward(user.id, 120, CreditReason.SIGNUP_REWARD, "signup:${user.id}")

        restTestClient.get()
            .uri("/api/v1/users/me/credits")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.balance").isEqualTo(120)
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        restTestClient.get()
            .uri("/api/v1/users/me/credits")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(UUID.randomUUID())}")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
