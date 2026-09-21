package com.knk.manyak.push.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.internal.shared-secret=internal-push-test-secret"],
)
class InternalPushEligibilityControllerIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var deviceTokens: DevicePushTokenRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() = cleaner.cleanAll()

    private fun saveUser(
        status: UserStatus = UserStatus.ACTIVE,
        servicePush: Boolean = true,
        marketing: Boolean = false,
        night: Boolean = false,
    ) = users.save(User(
        nickname = "발송자격회원",
        status = status,
        servicePushEnabled = servicePush,
        marketingPushAgreedAt = if (marketing) Instant.parse(DAY) else null,
        marketingPushNightAgreedAt = if (night) Instant.parse(DAY) else null,
    ))

    private fun saveToken(user: User, token: String = "android-token", platform: PushPlatform = PushPlatform.ANDROID, updatedAt: Instant = Instant.parse(DAY)) =
        deviceTokens.save(DevicePushToken(userId = user.id, token = token, platform = platform, updatedAt = updatedAt))

    private fun get(publicId: UUID, kind: String = "SERVICE", at: String = DAY, secret: String? = SECRET, bearer: String? = null): RestTestClient.ResponseSpec {
        val request = client.get().uri("/internal/users/$publicId/push-eligibility?kind=$kind&at=$at")
        secret?.let { request.header(HEADER, it) }
        bearer?.let { request.header("Authorization", it) }
        return request.exchange()
    }

    private fun expectDenied(response: RestTestClient.ResponseSpec, reason: String) {
        response.expectStatus().isOk.expectBody()
            .json("""{"allowed":false,"reason":"$reason","tokens":[]}""", JsonCompareMode.STRICT)
    }

    @Test
    fun `시크릿 헤더가 없으면 401이다`() {
        get(UUID.randomUUID(), secret = null).expectStatus().isUnauthorized
    }

    @ParameterizedTest
    @ValueSource(strings = ["wrong-secret", "internal-push-test-secret-extra", ""])
    fun `시크릿이 다르면 401이다`(secret: String) {
        get(UUID.randomUUID(), secret = secret).expectStatus().isUnauthorized
    }

    @Test
    fun `없는 publicId는 토큰 없이 USER_NOT_FOUND다`() {
        expectDenied(get(UUID.randomUUID()), "USER_NOT_FOUND")
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus::class, names = ["SUSPENDED", "DELETED"])
    fun `활성 회원이 아니면 동의와 토큰보다 먼저 거절한다`(status: UserStatus) {
        val user = saveUser(status = status, servicePush = false)
        saveToken(user)
        expectDenied(get(user.publicId), "NOT_ACTIVE")
    }

    @Test
    fun `서비스 알림을 끈 회원은 거절한다`() {
        val user = saveUser(servicePush = false)
        saveToken(user)
        expectDenied(get(user.publicId), "SERVICE_PUSH_DISABLED")
    }

    @Test
    fun `서비스 동의 회원의 두 플랫폼 토큰만 반환한다`() {
        val user = saveUser()
        saveToken(user, "android-token", updatedAt = Instant.parse(DAY).minusSeconds(1))
        saveToken(user, "web-token", PushPlatform.WEB)
        saveToken(saveUser(), "other-user-token")
        get(user.publicId, at = NIGHT).expectStatus().isOk.expectBody().json(
            """{"allowed":true,"reason":"OK","tokens":[{"token":"web-token","platform":"WEB"},{"token":"android-token","platform":"ANDROID"}]}""",
            JsonCompareMode.STRICT,
        )
    }

    @Test
    fun `광고 미동의는 서비스 동의 여부와 무관하게 거절한다`() {
        val user = saveUser()
        saveToken(user)
        expectDenied(get(user.publicId, kind = "MARKETING"), "MARKETING_NOT_AGREED")
    }

    @Test
    fun `광고 동의 회원은 KST 주간에 허용한다`() {
        val user = saveUser(servicePush = false, marketing = true)
        saveToken(user)
        get(user.publicId, kind = "MARKETING").expectStatus().isOk.expectBody()
            .jsonPath("$.allowed").isEqualTo(true)
            .jsonPath("$.reason").isEqualTo("OK")
            .jsonPath("$.tokens.length()").isEqualTo(1)
    }

    @Test
    fun `야간 광고 미동의는 같은 사유로 거절한다`() {
        val user = saveUser(marketing = true)
        saveToken(user)
        expectDenied(get(user.publicId, kind = "MARKETING", at = NIGHT), "MARKETING_NOT_AGREED")
    }

    @Test
    fun `광고와 야간에 모두 동의하면 야간에도 허용한다`() {
        val user = saveUser(marketing = true, night = true)
        saveToken(user)
        get(user.publicId, kind = "MARKETING", at = NIGHT).expectStatus().isOk.expectBody()
            .jsonPath("$.allowed").isEqualTo(true)
            .jsonPath("$.reason").isEqualTo("OK")
    }

    @ParameterizedTest
    @CsvSource(
        "2026-09-21T11:59:59Z, true",
        "2026-09-21T12:00:00Z, false",
        "2026-09-21T22:59:59Z, false",
        "2026-09-21T23:00:00Z, true",
    )
    fun `야간 경계는 호출자가 준 시각의 KST 기준이다`(at: String, allowed: Boolean) {
        val user = saveUser(marketing = true)
        saveToken(user)
        get(user.publicId, kind = "MARKETING", at = at).expectStatus().isOk.expectBody()
            .jsonPath("$.allowed").isEqualTo(allowed)
            .jsonPath("$.reason").isEqualTo(if (allowed) "OK" else "MARKETING_NOT_AGREED")
            .jsonPath("$.tokens.length()").isEqualTo(if (allowed) 1 else 0)
    }

    @Test
    fun `토큰이 없으면 NO_TOKENS다`() {
        expectDenied(get(saveUser().publicId), "NO_TOKENS")
    }

    @Test
    fun `토큰이 없어도 동의 거절이 먼저다`() {
        val user = saveUser(servicePush = false)
        expectDenied(get(user.publicId), "SERVICE_PUSH_DISABLED")
        expectDenied(get(user.publicId, kind = "MARKETING"), "MARKETING_NOT_AGREED")
    }

    @Test
    fun `기기가 11대면 최근 갱신 순으로 10개만 반환한다`() {
        val user = saveUser()
        (0..10).forEach { saveToken(user, "token-$it", updatedAt = Instant.parse(DAY).plusSeconds(it.toLong())) }
        val body = get(user.publicId).expectStatus().isOk.expectBody()
            .jsonPath("$.allowed").isEqualTo(true)
            .jsonPath("$.reason").isEqualTo("OK")
            .jsonPath("$.tokens.length()").isEqualTo(10)
        (0..9).forEach { body.jsonPath("$.tokens[$it].token").isEqualTo("token-${10 - it}") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["?kind=INVALID&at=2026-09-21T03:00:00Z", "?kind=SERVICE", "?at=2026-09-21T03:00:00Z", "?kind=SERVICE&at=invalid", "?kind=SERVICE&at="])
    fun `종류와 시각이 없거나 잘못되면 400이다`(query: String) {
        client.get().uri("/internal/users/${UUID.randomUUID()}/push-eligibility$query")
            .header(HEADER, SECRET).exchange().expectStatus().isBadRequest
    }

    @Test
    fun `publicId 형식이 잘못되면 400이다`() {
        client.get().uri("/internal/users/not-a-uuid/push-eligibility?kind=SERVICE&at=$DAY")
            .header(HEADER, SECRET).exchange().expectStatus().isBadRequest
    }

    @Test
    fun `유효한 사용자 토큰만으로 내부 인증을 통과하지 못한다`() {
        val user = saveUser()
        val bearer = "Bearer ${jwt.issueAccessToken(user.publicId)}"
        client.get().uri("/api/v1/auth/me").header("Authorization", bearer).exchange().expectStatus().isOk
        get(user.publicId, secret = null, bearer = bearer).expectStatus().isUnauthorized
    }

    @ParameterizedTest
    @EnumSource(UserStatus::class)
    fun `내부 인증은 사용자 토큰과 계정 상태를 해석하지 않는다`(status: UserStatus) {
        val caller = saveUser(status = status)
        expectDenied(get(UUID.randomUUID(), bearer = "Bearer ${jwt.issueAccessToken(caller.publicId)}"), "USER_NOT_FOUND")
    }

    @Test
    fun `잘못된 Bearer도 올바른 내부 시크릿을 막지 않는다`() {
        expectDenied(get(UUID.randomUUID(), bearer = "Bearer invalid"), "USER_NOT_FOUND")
    }

    @Test
    fun `동의를 철회한 뒤 다음 조회에서 즉시 거절한다`() {
        val user = saveUser(marketing = true)
        saveToken(user)
        get(user.publicId, kind = "MARKETING").expectStatus().isOk.expectBody().jsonPath("$.allowed").isEqualTo(true)
        user.marketingPushAgreedAt = null
        users.saveAndFlush(user)
        expectDenied(get(user.publicId, kind = "MARKETING"), "MARKETING_NOT_AGREED")
    }

    companion object {
        private const val HEADER = "X-Manyak-Internal-Secret"
        private const val SECRET = "internal-push-test-secret"
        private const val DAY = "2026-09-21T03:00:00Z"
        private const val NIGHT = "2026-09-21T13:00:00Z"
    }
}
