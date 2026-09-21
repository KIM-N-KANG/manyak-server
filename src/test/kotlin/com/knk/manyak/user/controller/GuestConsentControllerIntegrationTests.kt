package com.knk.manyak.user.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.global.observability.DeviceIdHasher
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
class GuestConsentControllerIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var hasher: DeviceIdHasher

    @BeforeEach
    fun setUp() = cleaner.cleanAll()

    private fun get(device: String = DEVICE) = client.get().uri(PATH).header(HEADER, device).exchange()
    private fun post(device: String = DEVICE, body: String = BODY) = client.post().uri(PATH)
        .header(HEADER, device).contentType(MediaType.APPLICATION_JSON).body(body).exchange()
    private fun rows() = jdbc.queryForList("SELECT device_id_hash, doc_type, version, agreed_at FROM guest_consents ORDER BY version")

    @Test
    fun `헤더 없는 조회와 기록은 400이다`() {
        client.get().uri(PATH).exchange().expectStatus().isBadRequest
        client.post().uri(PATH).contentType(MediaType.APPLICATION_JSON).body(BODY).exchange().expectStatus().isBadRequest
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   "])
    fun `공백 헤더의 조회와 기록은 400이다`(device: String) {
        get(device).expectStatus().isBadRequest
        post(device).expectStatus().isBadRequest
    }

    @Test
    fun `최초 기록 후 조회는 동의 상태이며 원본 대신 기존 해시를 저장한다`() {
        get().expectStatus().isOk.expectBody()
            .jsonPath("$.guestPrivacy.requiredVersion").isEqualTo("v1.0")
            .jsonPath("$.guestPrivacy.needsConsent").isEqualTo(true)
        post().expectStatus().isOk.expectBody().jsonPath("$.guestPrivacy.needsConsent").isEqualTo(false)
        get().expectStatus().isOk.expectBody().jsonPath("$.guestPrivacy.needsConsent").isEqualTo(false)
        assertThat(rows()).hasSize(1)
        assertThat(rows().single()["device_id_hash"]).isEqualTo(hasher.hash(DEVICE))
        assertThat(rows().single()["doc_type"]).isEqualTo("GUEST_PRIVACY")
    }

    @Test
    fun `버전 불일치는 저장 없이 지정 오류를 반환한다`() {
        post(body = """{"guestPrivacy":"old"}""").expectStatus().isBadRequest.expectBody()
            .jsonPath("$.code").isEqualTo("CONSENT_VERSION_MISMATCH")
        assertThat(rows()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", """{"guestPrivacy":null}"""])
    fun `미제출은 400이다`(body: String) {
        post(body = body).expectStatus().isBadRequest
        assertThat(rows()).isEmpty()
    }

    @Test
    fun `같은 버전 재제출은 최초 시각을 보존한다`() {
        post().expectStatus().isOk
        jdbc.update("UPDATE guest_consents SET agreed_at = TIMESTAMP WITH TIME ZONE '2020-01-01 00:00:00+00'")
        val first = rows()
        post().expectStatus().isOk
        assertThat(rows()).isEqualTo(first)
    }

    @Test
    fun `다른 디바이스는 독립이다`() {
        post().expectStatus().isOk
        get("another-device").expectStatus().isOk.expectBody().jsonPath("$.guestPrivacy.needsConsent").isEqualTo(true)
        post("another-device").expectStatus().isOk
        assertThat(rows()).hasSize(2)
    }

    @Test
    fun `과거 버전은 보존하고 현행 버전 동의를 별도로 받는다`() {
        jdbc.update("INSERT INTO guest_consents (device_id_hash, doc_type, version, agreed_at) VALUES (?, 'GUEST_PRIVACY', 'old', CURRENT_TIMESTAMP)", hasher.hash(DEVICE))
        get().expectStatus().isOk.expectBody().jsonPath("$.guestPrivacy.needsConsent").isEqualTo(true)
        post().expectStatus().isOk
        assertThat(rows().map { it["version"] }).containsExactlyInAnyOrder("old", "v1.0")
    }

    @ParameterizedTest
    @EnumSource(UserStatus::class)
    fun `회원 상태와 무관하게 토큰이 있어도 디바이스 기준으로 동작한다`(status: UserStatus) {
        val user = users.save(User(nickname = "동의회원", status = status))
        val bearer = "Bearer ${tokens.issueAccessToken(user.publicId)}"
        client.post().uri(PATH).header("Authorization", bearer).header(HEADER, DEVICE)
            .contentType(MediaType.APPLICATION_JSON).body(BODY).exchange().expectStatus().isOk
        client.get().uri(PATH).header("Authorization", bearer).header(HEADER, DEVICE)
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.guestPrivacy.needsConsent").isEqualTo(false)
        client.get().uri(PATH).header("Authorization", bearer).header(HEADER, "another-device")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.guestPrivacy.needsConsent").isEqualTo(true)
        client.get().uri(PATH).header("Authorization", bearer).exchange().expectStatus().isBadRequest
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents", Long::class.java)).isZero()
    }

    @Test
    fun `잘못된 토큰도 디바이스 동의를 막지 않는다`() {
        client.post().uri(PATH).header("Authorization", "Bearer invalid").header(HEADER, DEVICE)
            .contentType(MediaType.APPLICATION_JSON).body(BODY).exchange().expectStatus().isOk
    }

    companion object {
        private const val PATH = "/api/v1/guests/consents"
        private const val HEADER = RequestCorrelationFilter.HEADER_DEVICE_ID
        private const val DEVICE = "guest-consent-test-device"
        private const val BODY = """{"guestPrivacy":"v1.0"}"""
    }
}
