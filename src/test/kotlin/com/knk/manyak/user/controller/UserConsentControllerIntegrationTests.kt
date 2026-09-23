package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserConsentControllerIntegrationTests {
    @Autowired private lateinit var restTestClient: RestTestClient
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired private lateinit var databaseCleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() = databaseCleaner.cleanAll()

    private fun saveUser(status: UserStatus = UserStatus.ACTIVE) =
        userRepository.save(User(nickname = "동의회원", status = status))

    private fun bearer(user: User) = "Bearer ${jwtTokenProvider.issueAccessToken(user.publicId)}"
    private fun get(user: User) = restTestClient.get().uri(PATH).header("Authorization", bearer(user)).exchange()
    private fun post(user: User, body: String) = restTestClient.post().uri(PATH)
        .header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun rows(user: User) = jdbc.queryForList(
        "SELECT doc_type, version, agreed_at FROM user_consents WHERE user_id = ? ORDER BY doc_type", user.id,
    )

    @Test
    fun `최초 조회는 현행 버전 세 항목 모두 미동의다`() {
        get(saveUser()).expectStatus().isOk.expectBody()
            .jsonPath("$.terms.requiredVersion").isEqualTo("v1.4")
            .jsonPath("$.privacy.requiredVersion").isEqualTo("v1.7")
            .jsonPath("$.age14.requiredVersion").isEqualTo("1")
            .jsonPath("$.terms.needsConsent").isEqualTo(true)
            .jsonPath("$.privacy.needsConsent").isEqualTo(true)
            .jsonPath("$.age14.needsConsent").isEqualTo(true)
    }

    @Test
    fun `한 항목만 제출하면 그 항목만 저장한다`() {
        val user = saveUser()
        post(user, """{"terms":"v1.4"}""").expectStatus().isOk.expectBody()
            .jsonPath("$.terms.needsConsent").isEqualTo(false)
            .jsonPath("$.privacy.needsConsent").isEqualTo(true)
            .jsonPath("$.age14.needsConsent").isEqualTo(true)
        assertThat(rows(user)).hasSize(1)
        get(user).expectStatus().isOk.expectBody().jsonPath("$.terms.needsConsent").isEqualTo(false)
    }

    @Test
    fun `세 항목 제출 후 조회는 모두 동의 상태다`() {
        val user = saveUser()
        post(user, ALL).expectStatus().isOk.expectBody()
            .jsonPath("$.terms.needsConsent").isEqualTo(false)
            .jsonPath("$.privacy.needsConsent").isEqualTo(false)
            .jsonPath("$.age14.needsConsent").isEqualTo(false)
        get(user).expectStatus().isOk.expectBody()
            .jsonPath("$.terms.needsConsent").isEqualTo(false)
            .jsonPath("$.privacy.needsConsent").isEqualTo(false)
            .jsonPath("$.age14.needsConsent").isEqualTo(false)
        assertThat(rows(user)).hasSize(3)
        assertThat(userRepository.findById(user.id).orElseThrow().marketingPushAgreedAt).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"terms":"old"}""", """{"terms":"v1.4","privacy":"old"}""", """{"terms":"v1.4","privacy":"v1.7","age14":"2"}"""])
    fun `한 항목이라도 현행 버전과 다르면 전부 거부한다`(body: String) {
        val user = saveUser()
        post(user, body).expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("CONSENT_VERSION_MISMATCH")
        assertThat(rows(user)).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", """{"terms":null,"privacy":null,"age14":null}"""])
    fun `전부 미제출이면 400이다`(body: String) {
        val user = saveUser()
        post(user, body).expectStatus().isBadRequest
        assertThat(rows(user)).isEmpty()
    }

    @Test
    fun `중복 제출은 최초 동의 시각을 보존하고 생략은 철회가 아니다`() {
        val user = saveUser()
        post(user, ALL).expectStatus().isOk
        // 재삽입이 현재 시각으로 덮어쓰는지 확실히 구분한다.
        jdbc.update("UPDATE user_consents SET agreed_at = TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00' WHERE user_id = ?", user.id)
        val first = rows(user)
        post(user, ALL).expectStatus().isOk
        post(user, """{"terms":"v1.4","privacy":null}""").expectStatus().isOk
        assertThat(rows(user)).isEqualTo(first)
    }

    @Test
    fun `과거 버전 이력이 있어도 현행 버전은 별도로 받아 보존한다`() {
        val user = saveUser()
        jdbc.update("INSERT INTO user_consents (user_id, doc_type, version, agreed_at) VALUES (?, 'TERMS', 'v1.1', CURRENT_TIMESTAMP)", user.id)
        get(user).expectStatus().isOk.expectBody().jsonPath("$.terms.needsConsent").isEqualTo(true)
        post(user, """{"terms":"v1.4"}""").expectStatus().isOk
        assertThat(rows(user).map { it["version"] }).containsExactlyInAnyOrder("v1.1", "v1.4")
    }

    @Test
    fun `다른 회원의 동의는 승계하지 않는다`() {
        post(saveUser(), ALL).expectStatus().isOk
        get(saveUser()).expectStatus().isOk.expectBody().jsonPath("$.terms.needsConsent").isEqualTo(true)
    }

    @Test
    fun `정지 계정은 조회와 기록 모두 403이다`() {
        val user = saveUser(UserStatus.SUSPENDED)
        get(user).expectStatus().isForbidden
        post(user, ALL).expectStatus().isForbidden
        assertThat(rows(user)).isEmpty()
    }

    @Test
    fun `탈퇴 계정은 조회와 기록 모두 401이고 기존 이력은 보존한다`() {
        val user = saveUser()
        post(user, ALL).expectStatus().isOk
        user.status = UserStatus.DELETED
        userRepository.saveAndFlush(user)
        get(user).expectStatus().isUnauthorized
        post(user, ALL).expectStatus().isUnauthorized
        assertThat(rows(user)).hasSize(3)
    }

    @Test
    fun `존재하지 않는 회원 토큰은 조회와 기록 모두 401이다`() {
        val absent = User(nickname = "없는회원")
        get(absent).expectStatus().isUnauthorized
        post(absent, ALL).expectStatus().isUnauthorized
    }

    @Test
    fun `미인증 조회와 기록은 401이다`() {
        restTestClient.get().uri(PATH).exchange().expectStatus().isUnauthorized
        restTestClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).body(ALL)
            .exchange().expectStatus().isUnauthorized
    }

    companion object {
        private const val PATH = "/api/v1/users/me/consents"
        private const val ALL = """{"terms":"v1.4","privacy":"v1.7","age14":"1"}"""
    }
}
