package com.knk.manyak.invite.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
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
 * GET /api/v1/users/me/invite 통합 검증(KNK-393).
 * - 인증 필수: 토큰 없음·사용자 없음 → 401.
 * - 최초 조회는 코드를 발급하고, 재조회는 같은 코드를 돌려준다(안정적).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InviteIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(): User =
        userRepository.save(User(nickname = "초대유저", status = UserStatus.ACTIVE))

    private fun getInvite(token: String) =
        restTestClient.get()
            .uri("/api/v1/users/me/invite")
            .header("Authorization", "Bearer $token")
            .exchange()

    @Test
    fun `토큰 없이 초대 조회하면 401이다`() {
        restTestClient.get().uri("/api/v1/users/me/invite").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        getInvite(jwtTokenProvider.issueAccessToken(UUID.randomUUID())).expectStatus().isUnauthorized
    }

    @Test
    fun `정지된 회원은 초대 코드 조회가 403이고 코드가 발급되지 않는다`() {
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))

        getInvite(jwtTokenProvider.issueAccessToken(suspended.publicId)).expectStatus().isForbidden

        assertThat(userRepository.findById(suspended.id).orElseThrow().inviteCode).isNull()
    }

    @Test
    fun `최초 조회는 혼동 문자를 제외한 대문자+숫자 8자 코드를 발급한다`() {
        val user = saveUser()

        // KNK-567: 사람이 타이핑하는 값이라 O·0, I·1·L 등 혼동 문자를 제외한 대문자+숫자만 쓴다. inviteUrl은 폐기됐다.
        getInvite(jwtTokenProvider.issueAccessToken(user.publicId))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.inviteCode").value<String> { assertThat(it).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}") }
            .jsonPath("$.inviteUrl").doesNotExist()

        assertThat(userRepository.findById(user.id).orElseThrow().inviteCode).isNotBlank()
    }

    @Test
    fun `초대 조회는 이번 달 보상 진행과 월 상한을 함께 내려준다`() {
        val user = saveUser()

        // 보상을 받은 적 없는 신규 사용자는 진행 0, 상한은 정책 기본값(10).
        getInvite(jwtTokenProvider.issueAccessToken(user.publicId))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.monthlyRewardCount").isEqualTo(0)
            .jsonPath("$.monthlyRewardLimit").isEqualTo(10)
    }

    @Test
    fun `재조회는 같은 코드를 돌려준다`() {
        val user = saveUser()
        val token = jwtTokenProvider.issueAccessToken(user.publicId)

        val first = getInvite(token).expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val second = getInvite(token).expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        // 응답 본문(코드)이 결정적이므로, 재발급되면 두 본문이 달라진다. 안정적이어야 공유한 코드가 계속 유효하다.
        assertThat(first).contains("inviteCode")
        assertThat(second).isEqualTo(first)
    }
}
