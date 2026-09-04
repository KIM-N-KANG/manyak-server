package com.knk.manyak.push.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 알림 수신 동의 설정 통합 검증(KNK-1132, 정책 KNK-1129).
 * - 서비스 알림은 옵트아웃(기본 켜짐), 광고·야간 광고는 옵트인(기본 꺼짐)이다.
 * - 광고성 두 값의 저장 정본은 boolean이 아니라 **동의 시각**이다. 철회는 NULL이고, 재동의는 최초 시각을 유지한다.
 * - 야간 광고는 광고 동의 없이 단독으로 켤 수 없다(400 `NIGHT_PUSH_REQUIRES_MARKETING`).
 * - 인증 필수이며 정지 계정은 조회·변경 모두 403이다(설정 화면 자체를 못 쓴다).
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushSettingsControllerIntegrationTests {

    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    private fun saveUser(status: UserStatus = UserStatus.ACTIVE): User =
        userRepository.save(User(nickname = "설정유저", status = status))

    private fun bearer(user: User): String = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"

    private fun get(user: User) =
        restTestClient.get().uri(PATH).header("Authorization", bearer(user)).exchange()

    private fun put(user: User, body: String) =
        restTestClient.put()
            .uri(PATH)
            .header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange()

    private fun body(service: Boolean, marketing: Boolean, night: Boolean) =
        """{"servicePush":$service,"marketingPush":$marketing,"marketingNightPush":$night}"""

    private fun reload(user: User): User = userRepository.findById(user.id).orElseThrow()

    @Test
    fun `토큰 없이 조회하거나 변경하면 401이다`() {
        restTestClient.get().uri(PATH).exchange().expectStatus().isUnauthorized
        restTestClient.put()
            .uri(PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body(service = true, marketing = true, night = false))
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `기본값은 서비스 알림만 켜져 있다`() {
        get(saveUser())
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.servicePush").isEqualTo(true)
            .jsonPath("$.marketingPush").isEqualTo(false)
            .jsonPath("$.marketingNightPush").isEqualTo(false)
    }

    @Test
    fun `광고 알림을 켜면 동의 시각이 남고 야간은 그대로 비어 있다`() {
        val user = saveUser()

        put(user, body(service = true, marketing = true, night = false))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.marketingPush").isEqualTo(true)
            .jsonPath("$.marketingNightPush").isEqualTo(false)

        val reloaded = reload(user)
        assertThat(reloaded.marketingPushAgreedAt).isNotNull()
        assertThat(reloaded.marketingPushNightAgreedAt).isNull()
    }

    @Test
    fun `야간 광고를 켜면 야간 동의 시각도 남는다`() {
        val user = saveUser()

        put(user, body(service = true, marketing = true, night = true))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.marketingNightPush").isEqualTo(true)

        val reloaded = reload(user)
        assertThat(reloaded.marketingPushAgreedAt).isNotNull()
        assertThat(reloaded.marketingPushNightAgreedAt).isNotNull()
    }

    @Test
    fun `광고 알림을 끄면 광고와 야간 동의 시각이 함께 지워진다`() {
        val user = saveUser()
        put(user, body(service = true, marketing = true, night = true)).expectStatus().isOk

        put(user, body(service = true, marketing = false, night = false))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.marketingPush").isEqualTo(false)
            .jsonPath("$.marketingNightPush").isEqualTo(false)

        val reloaded = reload(user)
        assertThat(reloaded.marketingPushAgreedAt).isNull()
        assertThat(reloaded.marketingPushNightAgreedAt).isNull()
    }

    @Test
    fun `광고 동의 없이 야간만 켜면 400이고 아무것도 바뀌지 않는다`() {
        val user = saveUser()

        put(user, body(service = true, marketing = false, night = true))
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("NIGHT_PUSH_REQUIRES_MARKETING")

        val reloaded = reload(user)
        assertThat(reloaded.marketingPushAgreedAt).isNull()
        assertThat(reloaded.marketingPushNightAgreedAt).isNull()
        assertThat(reloaded.servicePushEnabled).isTrue()
    }

    @Test
    fun `재동의는 최초 동의 시각을 덮지 않는다`() {
        // 증빙은 최초 동의 시각이다. 토글을 껐다 켠 것이 아니라 같은 값을 다시 보낸 경우까지 갱신하면
        // "언제부터 동의했는가"가 매 요청마다 밀린다.
        val user = saveUser()
        put(user, body(service = true, marketing = true, night = false)).expectStatus().isOk
        val first = reload(user).marketingPushAgreedAt

        put(user, body(service = true, marketing = true, night = false)).expectStatus().isOk

        assertThat(reload(user).marketingPushAgreedAt).isEqualTo(first)
    }

    @Test
    fun `서비스 알림은 끌 수 있다`() {
        val user = saveUser()

        put(user, body(service = false, marketing = false, night = false))
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.servicePush").isEqualTo(false)

        assertThat(reload(user).servicePushEnabled).isFalse()
    }

    @Test
    fun `필드가 빠진 본문은 400이다`() {
        // 전체 교체 PUT이라 누락을 기본값으로 채우면 사용자가 보내지 않은 설정이 조용히 꺼진다.
        val user = saveUser()

        put(user, """{"servicePush":true,"marketingPush":true}""").expectStatus().isBadRequest

        assertThat(reload(user).marketingPushAgreedAt).isNull()
    }

    @Test
    fun `명시한 null도 필드 누락과 같이 400이다`() {
        // 명시한 null도 누락과 같이 거부해야 한다 — `"servicePush": null` 한 줄이 400이 아니라
        // "서비스 알림 끄기"로 조용히 반영되면 전체 교체 PUT의 silent wipe 방지 계약이 깨진다.
        // Jackson 3은 `FAIL_ON_NULL_FOR_PRIMITIVES`가 기본 on이라 primitive Boolean에 null을 넣으면
        // 역직렬화에서 거부되지만(Jackson 2 기본과 반대), 그 기본값이 바뀌면 조용히 깨지므로 여기서 고정한다.
        val user = saveUser()
        put(user, body(service = true, marketing = true, night = true)).expectStatus().isOk

        put(user, """{"servicePush":null,"marketingPush":true,"marketingNightPush":true}""")
            .expectStatus().isBadRequest

        val reloaded = reload(user)
        assertThat(reloaded.servicePushEnabled).isTrue()
        assertThat(reloaded.marketingPushNightAgreedAt).isNotNull()
    }

    @Test
    fun `광고 필드에 명시한 null도 400이고 동의가 철회되지 않는다`() {
        val user = saveUser()
        put(user, body(service = true, marketing = true, night = true)).expectStatus().isOk

        put(user, """{"servicePush":true,"marketingPush":null,"marketingNightPush":null}""")
            .expectStatus().isBadRequest

        val reloaded = reload(user)
        assertThat(reloaded.marketingPushAgreedAt).isNotNull()
        assertThat(reloaded.marketingPushNightAgreedAt).isNotNull()
    }

    @Test
    fun `정지된 계정은 조회도 변경도 403이다`() {
        val suspended = saveUser(status = UserStatus.SUSPENDED)

        get(suspended).expectStatus().isForbidden
        put(suspended, body(service = true, marketing = true, night = false)).expectStatus().isForbidden

        assertThat(reload(suspended).marketingPushAgreedAt).isNull()
    }

    companion object {
        private const val PATH = "/api/v1/users/me/push-settings"
    }
}
