package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.UUID

/**
 * POST /api/v1/users/me/credits/attendance 통합 검증(KNK-394).
 * - 인증 필수: 토큰 없음·사용자 없음 → 401.
 * - 첫 출석은 지급(rewarded=true), 같은 날 재출석은 멱등(rewarded=false·미증가).
 */
@org.springframework.context.annotation.Import(PaymentAnalyticsTestConfig::class)
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CreditAttendanceIntegrationTests {


    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var creditWalletService: CreditWalletService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @Autowired private lateinit var analytics: PaymentAnalyticsCapture

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
        analytics.events.clear()
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
        assertThat(analytics.events).isEmpty()
    }

    @Test
    fun `정지와 탈퇴 출석은 공통 오류로 거절하고 적립과 이벤트가 없다`() {
        for ((status, expected, code) in listOf(
            Triple(UserStatus.SUSPENDED, 403, "FORBIDDEN"),
            Triple(UserStatus.DELETED, 401, "UNAUTHORIZED"),
        )) {
            val user = userRepository.save(User(nickname = "출석 제한", status = status))
            attendance(jwtTokenProvider.issueAccessToken(user.publicId)).expectStatus().isEqualTo(expected)
                .expectBody().jsonPath("$.code").isEqualTo(code)
                .jsonPath("$.details.length()").isEqualTo(0)
            assertThat(creditWalletService.balanceOf(user.id)).isZero()
        }
        assertThat(analytics.events).isEmpty()
    }

    @Test
    fun `출석 이벤트는 요청 문맥을 쓰고 세션 조회는 정지 회원도 읽기만 한다`() {
        val user = saveUser()
        restTestClient.post().uri("/api/v1/users/me/credits/attendance")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}")
            .header("X-Manyak-Request-Id", "attendance-test-request")
            .header("X-Manyak-Device-Id", "attendance-device")
            .header("X-Manyak-Session-Id", "123456")
            .exchange().expectStatus().isOk
        val event = analytics.ofType("credit_attendance").single()
        assertThat(event.userId).isEqualTo(user.publicId.toString())
        assertThat(event.sessionId).isEqualTo(123456L)
        assertThat(event.deviceId).startsWith("device_hash_")
        assertThat(event.insertId).isEqualTo("attendance-test-request:${event.eventType}")
        assertThat(event.eventProperties).containsEntry("request_id", "attendance-test-request")
            .containsEntry("is_logged_in", true)
        user.status = UserStatus.SUSPENDED
        userRepository.saveAndFlush(user)
        restTestClient.get().uri("/api/v1/auth/me")
            .header("Authorization", "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.attendedToday").isEqualTo(true)
        assertThat(analytics.ofType("credit_attendance")).hasSize(1)
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
        val events = analytics.ofType("credit_attendance")
        assertThat(events).hasSize(2)
        assertThat(events.map { it.userId }).containsOnly(user.publicId.toString())
        assertThat(events.map { it.eventProperties["rewarded"] }).containsExactly(true, false)
        assertThat(events.map { it.eventProperties["amount"] }).containsExactly(afterFirst, 0L)
    }
}
