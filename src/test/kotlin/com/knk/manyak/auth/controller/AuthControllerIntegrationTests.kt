package com.knk.manyak.auth.controller

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
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

    @BeforeEach
    fun setUp() {
        userRepository.deleteAllInBatch()
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
}
