package com.knk.manyak.auth.controller

import com.knk.manyak.auth.config.AuthProperties
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.auth.token.InMemoryRefreshTokenStore
import com.knk.manyak.auth.token.RefreshTokenStore
import com.knk.manyak.support.DatabaseCleaner
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
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
 * - /token/refresh: 유효 → 새 토큰, 무효 → 401, 유예 안의 동일 refresh → 같은 원문. 후속 회전 뒤 과거 refresh 재사용 → 401.
 *
 * refresh 저장은 Redis 인프라 없이 검증하기 위해 InMemoryRefreshTokenStore(@Primary)로 대체한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIntegrationTests {


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
                profileThumbnailBase64 = "dGh1bWJuYWls",
                status = UserStatus.ACTIVE,
            ),
        )

    @Test
    fun `필수 경로의 토큰 누락 만료 위조 형식 오류는 같은 공통 JSON이다`() {
        val expired = JwtTokenProvider(properties, Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC))
            .issueAccessToken(UUID.randomUUID())
        val forged = JwtTokenProvider(properties.copy(secret = "different-key-for-conformance-test-32-bytes"))
            .issueAccessToken(UUID.randomUUID())
        for (path in listOf("/api/v1/auth/me", "/api/v1/users/me/personas", "/api/v1/stories/submissions", "/api/v1/users/me/chats")) {
            for (token in listOf(null, expired, forged, "invalid-token")) {
                val request = restTestClient.get().uri(path)
                if (token != null) request.header("Authorization", "Bearer $token")
                request.exchange().expectStatus().isUnauthorized.expectBody()
                    .jsonPath("$.timestamp").exists()
                    .jsonPath("$.status").isEqualTo(401)
                    .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                    .jsonPath("$.message").isEqualTo("유효하지 않은 인증입니다.")
                    .jsonPath("$.path").isEqualTo(path)
                    .jsonPath("$.details").isEmpty()
            }
        }
    }

    @Test
    fun `세션의 주체 누락 형식 오류 없는 회원 탈퇴 회원은 같은 401이다`() {
        val deleted = userRepository.save(User(nickname = "탈퇴회원", status = UserStatus.DELETED))
        for (subject in listOf(null, "bad-subject", UUID.randomUUID().toString(), deleted.publicId.toString())) {
            restTestClient.get().uri("/api/v1/auth/me")
                .header("Authorization", "Bearer ${signedToken(subject)}")
                .exchange().expectStatus().isUnauthorized.expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("유효하지 않은 인증입니다.")
                .jsonPath("$.details").isEmpty()
        }
    }

    @Test
    fun `없는 회원 토큰의 페르소나 목록 생성 수정 삭제는 같은 401이다`() {
        val auth = "Bearer ${jwtTokenProvider.issueAccessToken(UUID.randomUUID())}"
        val path = "/api/v1/users/me/personas"
        val id = UUID.randomUUID()
        val responses = listOf(
            restTestClient.get().uri(path).header("Authorization", auth).exchange(),
            restTestClient.post().uri(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .body("""{"name":"주인공","description":"설명"}""").exchange(),
            restTestClient.patch().uri("$path/$id").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .body("""{"name":"주인공"}""").exchange(),
            restTestClient.delete().uri("$path/$id").header("Authorization", auth).exchange(),
        )
        for (response in responses) {
            response.expectStatus().isUnauthorized.expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("유효하지 않은 인증입니다.")
        }
    }

    @Test
    fun `삭제된 핸드오프 세 경로는 인증 없으면 401이고 유효 인증이면 404다`() {
        val access = jwtTokenProvider.issueAccessToken(saveUser().publicId)
        for ((method, path) in listOf(
            org.springframework.http.HttpMethod.POST to "/api/v1/auth/handoffs",
            org.springframework.http.HttpMethod.GET to "/api/v1/auth/handoffs",
            org.springframework.http.HttpMethod.GET to "/api/v1/auth/handoffs/status",
        )) {
            for (token in listOf(null, "invalid-token", access)) {
                val request = restTestClient.method(method).uri(path)
                if (token != null) request.header("Authorization", "Bearer $token")
                request.contentType(MediaType.APPLICATION_JSON).body("""{"callbackPath":"/","sourceApp":"kakaotalk"}""")
                    .exchange().expectStatus().isEqualTo(if (token == access) 404 else 401)
            }
        }
    }

    @Test
    fun `미지원 소셜 제공자는 인증 없이 또는 잘못된 Bearer 헤더로도 400이다`() {
        for (provider in listOf("apple", "naver", "unknown")) {
            for (token in listOf(null, "invalid-token")) {
                val request = restTestClient.post().uri("/api/v1/auth/social/$provider")
                    .contentType(MediaType.APPLICATION_JSON).body("""{"idToken":"test-proof"}""")
                if (token != null) request.header("Authorization", "Bearer $token")
                request.exchange().expectStatus().isBadRequest.expectBody()
                    .jsonPath("$.code").isEqualTo("BAD_REQUEST")
                    .jsonPath("$.details").isEmpty()
            }
        }
    }

    private fun signedToken(subject: String?): String {
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800))
        if (subject != null) claims.subject(subject)
        val encoder = NimbusJwtEncoder(
            ImmutableSecret<SecurityContext>(
                properties.secret.toByteArray(Charsets.UTF_8),
            ),
        )
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(),
            claims.build(),
        )).tokenValue
    }

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
            // 첫 페인트용 인라인 썸네일(스펙 §4-3-5 B17).
            .jsonPath("$.profileThumbnailBase64").isEqualTo("dGh1bWJuYWls")
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
    fun `같은 refresh 연속 재발급은 모두 200이고 같은 refresh로 다음 회전도 성공한다`() {
        val issued = authTokenService.issueTokens(saveUser())
        val rotated = refreshSuccessfully(issued.refreshToken)
        val replayed = refreshSuccessfully(issued.refreshToken)

        assertThat(replayed.refreshToken).isEqualTo(rotated.refreshToken)
        assertThat(replayed.accessToken).isNotBlank()
        assertThat(refreshSuccessfully(replayed.refreshToken).refreshToken).isNotEqualTo(replayed.refreshToken)
    }

    @Test
    fun `후속 회전 후 가장 오래된 refresh 재사용은 401이다`() {
        val issued = authTokenService.issueTokens(saveUser())
        val rotated = refreshSuccessfully(issued.refreshToken)
        val current = refreshSuccessfully(rotated.refreshToken)

        for (token in listOf(issued.refreshToken, current.refreshToken)) {
            restTestClient.post().uri("/api/v1/auth/token/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"refreshToken":"$token"}""")
                .exchange().expectStatus().isUnauthorized
        }
    }

    private fun refreshSuccessfully(token: String): TokenResponse =
        restTestClient.post().uri("/api/v1/auth/token/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"refreshToken":"$token"}""")
            .exchange().expectStatus().isOk
            .expectBody(TokenResponse::class.java).returnResult().responseBody!!

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
