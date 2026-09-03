package com.knk.manyak.push.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.push.service.DevicePushTokenService
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.user.service.UserWithdrawalService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 디바이스 푸시 토큰 등록·삭제 통합 검증(KNK-1131).
 * - 인증 필수: 토큰 없음 → 401. 게스트는 대상이 아니다(회원 기기만 등록).
 * - 같은 토큰 재등록은 갱신(멱등)이라 중복 행이 생기지 않고, 다른 회원이 등록하면 소유자가 옮겨간다.
 * - 회원당 기기는 상한(MAX_DEVICES_PER_USER)까지만 남고, 넘기면 가장 오래 갱신되지 않은 기기가 빠진다.
 * - 삭제는 토큰을 **본문**으로 받고, 소유자만 지우며 멱등(없어도 204)이다. 탈퇴는 그 회원의 토큰을 전부 지운다.
 * - 정지(SUSPENDED) 계정은 등록·삭제 모두 403이다(스펙 §4-5 B20, Codex P2).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevicePushTokenControllerIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var devicePushTokenRepository: DevicePushTokenRepository
    @Autowired private lateinit var userWithdrawalService: UserWithdrawalService
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(nickname: String = "푸시유저"): User =
        userRepository.save(User(nickname = nickname, status = UserStatus.ACTIVE))

    private fun bearer(user: User): String = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun register(user: User, token: String, platform: String = "ANDROID") =
        restTestClient.put()
            .uri(PATH)
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"token":"$token","platform":"$platform"}""")
            .exchange()

    // 삭제는 토큰을 본문으로 받는다(경로에 실으면 원문이 요청 로그에 남는다). delete()는 본문을 못 실어 method()로 보낸다.
    private fun unregister(user: User, token: String) =
        restTestClient.method(HttpMethod.DELETE)
            .uri(PATH)
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"token":"$token"}""")
            .exchange()

    @Test
    fun `토큰 없이 등록하면 401이다`() {
        restTestClient.put()
            .uri(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"token":"$TOKEN_A","platform":"ANDROID"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `등록하면 204이고 그 회원의 행이 하나 생긴다`() {
        val user = saveUser()

        register(user, TOKEN_A).expectStatus().isNoContent

        val rows = devicePushTokenRepository.findAll()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().userId).isEqualTo(user.id)
        assertThat(rows.single().token).isEqualTo(TOKEN_A)
        assertThat(rows.single().platform).isEqualTo(PushPlatform.ANDROID)
    }

    @Test
    fun `같은 토큰을 다시 등록해도 행은 하나다`() {
        val user = saveUser()

        register(user, TOKEN_A).expectStatus().isNoContent
        register(user, TOKEN_A).expectStatus().isNoContent

        assertThat(devicePushTokenRepository.findAll()).hasSize(1)
    }

    @Test
    fun `다른 회원이 같은 토큰을 등록하면 소유자가 옮겨간다`() {
        val first = saveUser("첫째")
        val second = saveUser("둘째")

        register(first, TOKEN_A).expectStatus().isNoContent
        register(second, TOKEN_A).expectStatus().isNoContent

        val rows = devicePushTokenRepository.findAll()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().userId).isEqualTo(second.id)
    }

    @Test
    fun `한 회원이 기기 두 대를 등록하면 행이 둘이다`() {
        val user = saveUser()

        register(user, TOKEN_A).expectStatus().isNoContent
        register(user, TOKEN_B).expectStatus().isNoContent

        assertThat(devicePushTokenRepository.findAll()).hasSize(2)
    }

    @Test
    fun `상한을 넘겨 등록하면 가장 안 쓰던 기기가 빠진다`() {
        val user = saveUser()
        val cap = DevicePushTokenService.MAX_DEVICES_PER_USER
        val tokens = (1..cap + 1).map { "dEv1cE_$it:APA91bCapToken$it-0123456789_abcdefghijklmnopqrstuvwxyz" }

        tokens.forEach { register(user, it).expectStatus().isNoContent }

        val rows = devicePushTokenRepository.findAll()
        assertThat(rows).hasSize(cap)
        assertThat(rows.map { it.token }).doesNotContain(tokens.first()).contains(tokens.last())
    }

    @Test
    fun `상한을 채운 회원이 남의 토큰을 가져와도 상한이 유지된다`() {
        val giver = saveUser("양도자")
        val taker = saveUser("수령자")
        val cap = DevicePushTokenService.MAX_DEVICES_PER_USER
        val moved = "dEv1cE_moved:APA91bMovedToken-0123456789_abcdefghijklmnopqrstuvwxyz"
        register(giver, moved).expectStatus().isNoContent
        val takerTokens = (1..cap).map { "dEv1cE_t$it:APA91bTakerToken$it-0123456789_abcdefghijklmnopqrstuvwxyz" }
        takerTokens.forEach { register(taker, it).expectStatus().isNoContent }

        // 한 기기에서 계정을 바꾼 상황. 수령자에게는 기기가 하나 늘어나므로 삽입과 같은 규칙으로 축출돼야 한다.
        register(taker, moved).expectStatus().isNoContent

        val rows = devicePushTokenRepository.findAll()
        assertThat(rows).hasSize(cap)
        assertThat(rows).allMatch { it.userId == taker.id }
        assertThat(rows.map { it.token })
            .contains(moved)
            .doesNotContain(takerTokens.first())
    }

    @Test
    fun `빈 토큰은 400이다`() {
        register(saveUser(), "   ").expectStatus().isBadRequest
    }

    @Test
    fun `알 수 없는 platform은 400이다`() {
        register(saveUser(), TOKEN_A, platform = "WINDOWS_PHONE").expectStatus().isBadRequest
    }

    @Test
    fun `아직 지원하지 않는 IOS도 400이다`() {
        register(saveUser(), TOKEN_A, platform = "IOS").expectStatus().isBadRequest
    }

    @Test
    fun `삭제하면 204이고 행이 사라지며 다시 삭제해도 204다`() {
        val user = saveUser()
        register(user, TOKEN_A).expectStatus().isNoContent

        unregister(user, TOKEN_A).expectStatus().isNoContent
        assertThat(devicePushTokenRepository.findAll()).isEmpty()

        unregister(user, TOKEN_A).expectStatus().isNoContent
    }

    @Test
    fun `다른 회원의 토큰은 삭제해도 남는다`() {
        val owner = saveUser("소유자")
        val other = saveUser("타인")
        register(owner, TOKEN_A).expectStatus().isNoContent

        unregister(other, TOKEN_A).expectStatus().isNoContent

        assertThat(devicePushTokenRepository.findAll()).hasSize(1)
    }

    @Test
    fun `정지된 계정은 등록하지 못한다`() {
        val suspended = userRepository.save(User(nickname = "정지회원", status = UserStatus.SUSPENDED))

        register(suspended, TOKEN_A).expectStatus().isForbidden

        assertThat(devicePushTokenRepository.findAll()).isEmpty()
    }

    @Test
    fun `정지된 계정은 삭제하지 못한다`() {
        val user = saveUser()
        register(user, TOKEN_A).expectStatus().isNoContent
        user.status = UserStatus.SUSPENDED
        userRepository.save(user)

        unregister(user, TOKEN_A).expectStatus().isForbidden

        assertThat(devicePushTokenRepository.findAll()).hasSize(1)
    }

    @Test
    fun `토큰 없이 삭제하면 401이다`() {
        restTestClient.method(HttpMethod.DELETE)
            .uri(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"token":"$TOKEN_A"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `삭제 본문의 token이 비면 400이다`() {
        unregister(saveUser(), "   ").expectStatus().isBadRequest
    }

    @Test
    fun `탈퇴하면 그 회원의 토큰이 전부 지워진다`() {
        val leaving = saveUser("탈퇴자")
        val staying = saveUser("잔류자")
        register(leaving, TOKEN_A).expectStatus().isNoContent
        register(leaving, TOKEN_B).expectStatus().isNoContent
        register(staying, TOKEN_C).expectStatus().isNoContent

        userWithdrawalService.withdraw(leaving.id)

        val rows = devicePushTokenRepository.findAll()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().userId).isEqualTo(staying.id)
    }

    companion object {
        private const val PATH = "/api/v1/users/me/push-tokens"
        // FCM 토큰 형태를 흉내 낸다(콜론·하이픈·언더스코어 포함).
        private const val TOKEN_A = "dEv1cE_a:APA91bFakeTokenA-0123456789_abcdefghijklmnopqrstuvwxyz"
        private const val TOKEN_B = "dEv1cE_b:APA91bFakeTokenB-0123456789_abcdefghijklmnopqrstuvwxyz"
        private const val TOKEN_C = "dEv1cE_c:APA91bFakeTokenC-0123456789_abcdefghijklmnopqrstuvwxyz"
    }
}
