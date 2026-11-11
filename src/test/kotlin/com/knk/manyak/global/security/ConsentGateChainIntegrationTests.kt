package com.knk.manyak.global.security

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.ConsentGateIntegrationTest
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.EntityExchangeResult
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper

/**
 * 동의 게이트(ACCOUNT 5-1-3)를 실제 필터 체인으로 고정한다(KNK-2068). 허용 목록은 메서드와 정확한 경로로 판정한다.
 * 판정 코드를 옮기는 동안 거절 본문과 허용 목록이 그대로인지 확인하는 안전망이다.
 */
class ConsentGateChainIntegrationTests : ConsentGateIntegrationTest() {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var tokens: JwtTokenProvider

    private val mapper = ObjectMapper()

    @BeforeEach fun clean() = cleaner.cleanAll()

    @Test
    fun `미동의 회원은 필수 경로와 선택 인증 경로 모두 같은 본문의 403이다`() {
        val token = unconsentedToken()
        for ((method, path) in listOf(HttpMethod.GET to "/api/v1/users/me/credits", HttpMethod.GET to "/api/v1/stories")) {
            val result = exchange(method, path, token)
            assertThat(result.status.value()).describedAs(path).isEqualTo(403)
            assertThat(result.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE)).describedAs(path).isNull()
            val body = mapper.readTree(result.responseBody)
            assertThat(body.propertyNames().toList()).containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path", "details")
            assertThat(body["status"].asInt()).isEqualTo(403)
            assertThat(body["code"].asText()).isEqualTo("CONSENT_REQUIRED")
            assertThat(body["message"].asText()).isEqualTo("현행 필수 항목에 동의해 주세요.")
            assertThat(body["path"].asText()).isEqualTo(path)
            assertThat(body["details"].isEmpty).isTrue()
        }
    }

    @Test
    fun `허용 목록의 메서드와 경로만 게이트를 지나고 메서드가 다르면 막힌다`() {
        val soft = SoftAssertions()
        // 요청마다 새 회원을 쓴다(탈퇴 요청이 계정 상태를 바꾸므로 서로 영향이 없게 한다).
        for ((method, path) in ALLOWED) {
            val result = exchange(method, path, unconsentedToken())
            soft.assertThat(isConsentRejection(result)).describedAs("허용 $method $path -> ${result.status}").isFalse()
        }
        for ((method, path) in NOT_ALLOWED) {
            val result = exchange(method, path, unconsentedToken())
            soft.assertThat(isConsentRejection(result)).describedAs("차단 $method $path -> ${result.status}").isTrue()
        }
        soft.assertAll()
    }

    private fun unconsentedToken(): String = tokens.issueAccessToken(users.save(User(nickname = "미동의")).publicId)

    private fun exchange(method: HttpMethod, path: String, token: String): EntityExchangeResult<String> =
        client.method(method).uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .apply { if (method == HttpMethod.POST || method == HttpMethod.PUT) contentType(MediaType.APPLICATION_JSON).body("{}") }
            .exchange().returnResult(String::class.java)

    private fun isConsentRejection(result: EntityExchangeResult<String>): Boolean =
        result.status.value() == 403 && result.responseBody?.contains("\"CONSENT_REQUIRED\"") == true

    private companion object {
        /** ACCOUNT 5-1-3 동의 게이트 허용 목록. */
        val ALLOWED = listOf(
            HttpMethod.GET to "/api/v1/users/me/consents",
            HttpMethod.POST to "/api/v1/users/me/consents",
            HttpMethod.GET to "/api/v1/auth/me",
            HttpMethod.DELETE to "/api/v1/users/me/push-tokens",
            HttpMethod.GET to "/api/v1/credits/policies",
            HttpMethod.GET to "/api/v1/credits/products",
            HttpMethod.GET to "/api/v1/profile-presets",
            HttpMethod.GET to "/api/v1/stories/simple/tags",
            HttpMethod.GET to "/api/v1/stories/genres",
            HttpMethod.GET to "/api/v1/stories/art-styles",
            HttpMethod.DELETE to "/api/v1/users/me",
        )

        /** 허용 경로와 메서드나 경로가 조금씩 다른 요청. 정확 일치만 허용하는지 확인한다. */
        val NOT_ALLOWED = listOf(
            HttpMethod.PUT to "/api/v1/users/me/push-tokens",
            HttpMethod.GET to "/api/v1/users/me",
            HttpMethod.GET to "/api/v1/users/me/trials",
            HttpMethod.POST to "/api/v1/stories/simple/tags",
        )
    }
}
