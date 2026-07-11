package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * POST /api/v1/users/me/credits/attendance 통합 검증(KNK-394).
 * - 인증 필수: 토큰 없음·사용자 없음 → 401.
 * - 첫 출석은 지급(rewarded=true), 같은 날 재출석은 멱등(rewarded=false·미증가).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditAttendanceIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

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
        userRepository.save(User(nickname = "출석유저", status = UserStatus.ACTIVE))

    private fun attendance(token: String) =
        restTestClient.post()
            .uri("/api/v1/users/me/credits/attendance")
            .header("Authorization", "Bearer $token")
            .exchange()

    @Test
    fun `토큰 없이 출석하면 401이다`() {
        restTestClient.post().uri("/api/v1/users/me/credits/attendance").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        attendance(jwtTokenProvider.issueAccessToken(UUID.randomUUID())).expectStatus().isUnauthorized
    }

    @Test
    fun `첫 출석은 보상을 지급한다`() {
        val user = saveUser()

        attendance(jwtTokenProvider.issueAccessToken(user.publicId))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.rewarded").isEqualTo(true)
            .jsonPath("$.amount").value<Int> { assertThat(it).isGreaterThan(0) }
            .jsonPath("$.balance").value<Int> { assertThat(it).isGreaterThan(0) }

        assertThat(creditWalletService.balanceOf(user.id)).isGreaterThan(0)
    }

    @Test
    fun `같은 날 재출석하면 멱등하게 rewarded=false고 잔액이 그대로다`() {
        val user = saveUser()
        val token = jwtTokenProvider.issueAccessToken(user.publicId)

        attendance(token).expectStatus().isOk.expectBody().jsonPath("$.rewarded").isEqualTo(true)
        val afterFirst = creditWalletService.balanceOf(user.id)

        attendance(token)
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.rewarded").isEqualTo(false)
            .jsonPath("$.amount").isEqualTo(0)
            .jsonPath("$.balance").isEqualTo(afterFirst.toInt())

        assertThat(creditWalletService.balanceOf(user.id)).isEqualTo(afterFirst)
    }
}
