package com.knk.manyak.auth.controller

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.AttendanceRewardService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.support.DatabaseCleaner
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * /api/v1/auth (/me, /token/refresh) 통합 검증.
 *
 * - /me: 토큰 없음·위조·만료 → 401, 유효 access → 200+본문, 사용자 없는 토큰 → 401.
 * - /token/refresh: 유효 → 새 토큰, 무효 → 401, 회전 후 옛 refresh 재사용 → 401.
 *
 * refresh 저장은 Redis 인프라 없이 검증하기 위해 InMemoryRefreshTokenStore(@Primary)로 대체한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIntegrationTests {

    @TestConfiguration
    class InMemoryStoreConfig {
        // 테스트 컨텍스트에서는 Redis 대신 in-memory 저장소를 쓴다(RedisRefreshTokenStore 대체).
        @Bean
        @Primary
        fun inMemoryRefreshTokenStore(): RefreshTokenStore = InMemoryRefreshTokenStore()
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var authTokenService: AuthTokenService

    @Autowired
    private lateinit var properties: AuthProperties

    @Autowired
    private lateinit var creditWalletService: CreditWalletService

    @Autowired
    private lateinit var attendanceRewardService: AttendanceRewardService

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(): User =
        userRepository.save(
            User(
                nickname = "manyak_user",
                profileImageUrl = "https://example.com/profile.png",
                status = UserStatus.ACTIVE,
            ),
        )

    // ---- GET /me ----

    @Test
    fun `토큰 없이 me를 호출하면 401이다`() {
        restTestClient.get()
            .uri("/api/v1/auth/me")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `위조한 토큰으로 me를 호출하면 401이다`() {
        val forger = JwtTokenProvider(
            AuthProperties(
                secret = "totally-different-secret-32-bytes-minimum-xx",
                issuer = properties.issuer,
                accessTtl = properties.accessTtl,
                refreshTtl = properties.refreshTtl,
            ),
        )
        val forged = forger.issueAccessToken(UUID.randomUUID())

        restTestClient.get()
            .uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $forged")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `만료된 토큰으로 me를 호출하면 401이다`() {
        val user = saveUser()
        val past = Instant.now().minus(Duration.ofHours(2))
        val expiredIssuer = JwtTokenProvider(properties, Clock.fixed(past, ZoneOffset.UTC))
        val expired = expiredIssuer.issueAccessToken(user.publicId)

        restTestClient.get()
            .uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $expired")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 access 토큰으로 me를 호출하면 200과 사용자 정보를 반환한다`() {
        val user = saveUser()
        val accessToken = jwtTokenProvider.issueAccessToken(user.publicId)

        restTestClient.get()
            .uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $accessToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(user.publicId.toString())
            .jsonPath("$.nickname").isEqualTo("manyak_user")
            .jsonPath("$.profileImageUrl").isEqualTo("https://example.com/profile.png")
            .jsonPath("$.status").isEqualTo("ACTIVE")
    }

    @Test
    fun `활동이 없는 사용자의 me 응답은 creditBalance 0, attendedToday false다`() {
        val user = saveUser()
        val accessToken = jwtTokenProvider.issueAccessToken(user.publicId)

        restTestClient.get()
            .uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $accessToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.creditBalance").isEqualTo(0)
            .jsonPath("$.attendedToday").isEqualTo(false)
    }

    @Test
    fun `크레딧 적립과 출석 후 me 응답에 잔액과 출석 여부가 반영된다`() {
        val user = saveUser()
        val accessToken = jwtTokenProvider.issueAccessToken(user.publicId)
        creditWalletService.reward(user.id, 500, CreditReason.SIGNUP_REWARD, "signup:${user.id}")
        attendanceRewardService.claimDailyAttendance(user.id)
        val expectedBalance = creditWalletService.balanceOf(user.id)

        restTestClient.get()
            .uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $accessToken")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.creditBalance").isEqualTo(expectedBalance)
            .jsonPath("$.attendedToday").isEqualTo(true)
    }

    @Test
    fun `유효한 토큰이지만 사용자가 없으면 401이다`() {
        // 토큰 서명은 유효하지만 sub가 가리키는 사용자가 DB에 없는 경우.
        val accessToken = jwtTokenProvider.issueAccessToken(UUID.randomUUID())

        restTestClient.get()
            .uri("/api/v1/auth/me")
            .header("Authorization", "Bearer $accessToken")
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ---- POST /api/v1/auth/token/refresh ----

    @Test
    fun `정지된 계정은 refresh 회전이 403이고 토큰 계열이 폐기된다`() {
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)
        user.status = UserStatus.SUSPENDED
        userRepository.save(user)

        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `유효한 refresh로 회전하면 200과 새 토큰을 반환한다`() {
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)

        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.accessToken").isNotEmpty
            .jsonPath("$.refreshToken").isNotEmpty
            .jsonPath("$.tokenType").isEqualTo("Bearer")
            .jsonPath("$.expiresIn").isEqualTo(properties.accessTtl.seconds)
    }

    @Test
    fun `만료된 Authorization Bearer 헤더가 붙어도 유효한 refresh면 200으로 회전한다`() {
        // 모바일 인터셉터가 만료 access 토큰을 모든 요청에 자동 첨부하는 시나리오.
        // refresh 경로는 bearerTokenResolver에서 토큰을 무시하므로, 만료 헤더로 401이 나면 안 된다.
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)
        val past = Instant.now().minus(Duration.ofHours(2))
        val expiredAccess = JwtTokenProvider(properties, Clock.fixed(past, ZoneOffset.UTC))
            .issueAccessToken(user.publicId)

        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .header("Authorization", "Bearer $expiredAccess")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.accessToken").isNotEmpty
            .jsonPath("$.refreshToken").isNotEmpty
    }

    @Test
    fun `위조된 Authorization Bearer 헤더가 붙어도 유효한 refresh면 200으로 회전한다`() {
        // 서명 검증 실패 토큰이 자동 첨부돼도 refresh 경로에서는 토큰을 무시한다.
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)

        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .header("Authorization", "Bearer not-a-real-jwt.forged.token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `무효한 refresh로 회전하면 401이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"never-issued-token"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `refreshToken이 비어 있으면 400이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":""}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `회전 후 옛 refresh를 재사용하면 401이다`() {
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)

        // 1차 회전: 성공하고 새 토큰을 받는다.
        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isOk

        // 같은(옛) refresh 재사용: 이미 폐기됐으므로 401.
        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    // ---- POST /api/v1/auth/logout ----

    @Test
    fun `유효한 refresh로 로그아웃하면 204이고 그 refresh로는 회전이 401이다`() {
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)

        // 로그아웃: refresh 폐기. 204 No Content.
        restTestClient.post()
            .uri("/api/v1/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isNoContent

        // 폐기됐으므로 그 refresh로는 회전 불가(401).
        restTestClient.post()
            .uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `발급된 적 없는 refresh로 로그아웃해도 멱등하게 204다`() {
        // 멱등: 없는 토큰 폐기는 무시하고 204로 응답한다(존재 여부를 노출하지 않는다).
        restTestClient.post()
            .uri("/api/v1/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"never-issued-token"}""")
            .exchange()
            .expectStatus().isNoContent
    }

    @Test
    fun `logout 본문에 refreshToken이 비어 있으면 400이다`() {
        restTestClient.post()
            .uri("/api/v1/auth/logout")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":""}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `만료된 Authorization Bearer 헤더가 붙어도 유효한 refresh면 204로 로그아웃한다`() {
        // 모바일 인터셉터가 만료 access 토큰을 자동 첨부하는 시나리오.
        // logout 경로도 refresh와 동일하게 bearer-skip이므로 만료 헤더로 401이 나면 안 된다.
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)
        val past = Instant.now().minus(Duration.ofHours(2))
        val expiredAccess = JwtTokenProvider(properties, Clock.fixed(past, ZoneOffset.UTC))
            .issueAccessToken(user.publicId)

        restTestClient.post()
            .uri("/api/v1/auth/logout")
            .header("Authorization", "Bearer $expiredAccess")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isNoContent
    }

    @Test
    fun `위조된 Authorization Bearer 헤더가 붙어도 유효한 refresh면 204로 로그아웃한다`() {
        // 서명 검증 실패 토큰이 자동 첨부돼도 logout 경로에서는 토큰을 무시한다(bearer-skip).
        val user = saveUser()
        val issued = authTokenService.issueTokens(user)

        restTestClient.post()
            .uri("/api/v1/auth/logout")
            .header("Authorization", "Bearer not-a-real-jwt.forged.token")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"${issued.refreshToken}"}""")
            .exchange()
            .expectStatus().isNoContent
    }
}
