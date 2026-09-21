package com.knk.manyak.push.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.internal.shared-secret=internal-push-test-secret"])
class InternalPushTokenControllerIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: DevicePushTokenRepository
    @Autowired private lateinit var cleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() = cleaner.cleanAll()

    private fun delete(body: String?, secret: String? = "internal-push-test-secret"): RestTestClient.ResponseSpec {
        val request = client.method(HttpMethod.DELETE).uri("/internal/push-tokens").contentType(MediaType.APPLICATION_JSON)
        secret?.let { request.header("X-Manyak-Internal-Secret", it) }
        body?.let { request.body(it) }
        return request.exchange()
    }

    @Test
    fun `토큰 값으로 삭제하고 다른 토큰은 보존한다`() {
        val user = users.save(User(nickname = "토큰회원"))
        val target = tokens.save(DevicePushToken(userId = user.id, token = "invalid-token", platform = PushPlatform.ANDROID))
        val other = tokens.save(DevicePushToken(userId = user.id, token = "other-token", platform = PushPlatform.WEB))
        delete("""{"token":"invalid-token"}""").expectStatus().isNoContent
        assertThat(tokens.existsById(target.id)).isFalse()
        assertThat(tokens.existsById(other.id)).isTrue()
        delete("""{"token":"invalid-token"}""").expectStatus().isNoContent
    }

    @Test
    fun `없는 토큰도 204다`() {
        delete("""{"token":"missing"}""").expectStatus().isNoContent
    }

    @Test
    fun `헤더가 없으면 401이다`() {
        delete("""{"token":"missing"}""", null).expectStatus().isUnauthorized
    }

    @Test
    fun `시크릿이 다르면 401이다`() {
        delete("""{"token":"missing"}""", "wrong").expectStatus().isUnauthorized
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}", "{\"token\":\"\"}", "{\"token\":\"  \"}", "{\"token\":null}"])
    fun `토큰 누락과 빈 값은 400이다`(body: String) {
        delete(body).expectStatus().isBadRequest
    }

    @Test
    fun `본문 누락은 400이다`() {
        delete(null).expectStatus().isBadRequest
    }
}
