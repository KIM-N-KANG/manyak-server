package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneId

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAnalyticsIntegrationTests {
    companion object {
        private val server = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("manyak.analytics.amplitude.api-key") { "mock-api" }
            registry.add("manyak.analytics.amplitude.secret-key") { "mock-secret" }
            registry.add("manyak.analytics.amplitude.dashboard-base-url") { server.url("/").toString() }
        }
        @JvmStatic @AfterAll fun stop() { server.shutdown() }
    }
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: ObjectMapper
    @Test fun `관리자 인증과 기간 검증 뒤 HTTP 대역의 분석 지표를 반환하고 기간별 캐시를 공유한다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자")); val member = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        val path = "/api/v1/admin/dashboard/analytics"
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAccessToken(admin.publicId)}").exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAdminToken(member.publicId)}").exchange().expectStatus().isForbidden
        for (days in listOf("10", "bad", "")) client.get().uri("$path?days=$days").header("Authorization", "Bearer $token").exchange().expectStatus().isBadRequest
        assertThat(server.requestCount).isZero()
        val end = LocalDate.now(ZoneId.of("Asia/Seoul"))
        val body = mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to (0L..29).map { end.minusDays(29).plusDays(it).toString() }, "series" to listOf(List(30) { 12 }))))
        repeat(2) { server.enqueue(MockResponse().setBody(body)) }
        client.get().uri(path).header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.daily.length()").isEqualTo(30).jsonPath("$.to").isEqualTo(end.toString()).jsonPath("$.todayActiveUsers").isEqualTo(12)
        client.get().uri("$path?from=${end.minusDays(29)}&to=$end").header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody().jsonPath("$.daily.length()").isEqualTo(30)
        assertThat(server.requestCount).isEqualTo(2)
        for (metric in listOf("active", "new")) {
            val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(request.requestUrl!!.encodedPath).isEqualTo("/api/2/users")
            assertThat(request.requestUrl!!.queryParameter("m")).isEqualTo(metric)
            assertThat(request.requestUrl!!.queryParameter("i")).isEqualTo("1")
            assertThat(request.requestUrl!!.queryParameter("e")).isNull()
        }
        val start = end.minusDays(100)
        val finish = start.plusDays(1)
        val custom = mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to listOf(start.toString(), finish.toString()), "series" to listOf(listOf(3, 4)))))
        repeat(2) { server.enqueue(MockResponse().setBody(custom)) }
        server.enqueue(MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to listOf(end.toString()), "series" to listOf(listOf(17)))))))
        repeat(2) {
            client.get().uri("$path?from=$start&to=$finish").header("Authorization", "Bearer $token").exchange()
                .expectStatus().isOk.expectBody().jsonPath("$.from").isEqualTo(start.toString())
                .jsonPath("$.to").isEqualTo(finish.toString()).jsonPath("$.daily.length()").isEqualTo(2)
                .jsonPath("$.todayActiveUsers").isEqualTo(17)
        }
        assertThat(server.requestCount).isEqualTo(5)
        repeat(2) {
            val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertThat(request.requestUrl!!.queryParameter("start")).isEqualTo(start.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
            assertThat(request.requestUrl!!.queryParameter("end")).isEqualTo(finish.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
        }

    }
}
