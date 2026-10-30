package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE

@AutoConfigureRestTestClient
@Import(GrowthEndpointClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrowthEndpointsIntegrationTests {
    companion object {
        private val mapper = ObjectMapper()
        private val server = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("manyak.analytics.amplitude.api-key") { "mock" }
            r.add("manyak.analytics.amplitude.secret-key") { "mock" }
            r.add("manyak.analytics.amplitude.dashboard-base-url") { server.url("/").toString() }
        }
        @JvmStatic @AfterAll fun stop() { server.shutdown() }
    }
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Test fun `새 성장 API 다섯 개는 관리자 인증 계약 필드와 기간 검증을 적용한다`() {
        cleaner.cleanAll()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val u = r.requestUrl!!
                val dates = AnalyticsRange(LocalDate.parse(u.queryParameter("start"), BASIC_ISO_DATE), LocalDate.parse(u.queryParameter("end"), BASIC_ISO_DATE)).dates()
                val data: Any = when {
                    u.encodedPath.endsWith("/funnels") -> listOf(mapOf("cumulativeRaw" to List(u.queryParameterValues("e").size) { 10-it }))
                    u.queryParameter("g") != null -> mapOf("xValues" to dates.map { it.toString() }, "seriesLabels" to listOf(listOf(0,"(none)")), "series" to listOf(dates.map { 2 }))
                    else -> mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(dates.map { 10 }), "seriesCollapsed" to listOf(listOf(mapOf("value" to 12))))
                }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
        val admin = users.save(User(nickname = "관리자")); val ordinary = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        val routes = listOf("/campaigns/funnels/activation", "/campaigns/reach", "/campaigns/creatives", "/campaigns/app-transfer", "/users/app-activation")
        fun get(path: String) = client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer $token").exchange()
        for (path in routes) {
            client.get().uri("/api/v1/admin$path").exchange().expectStatus().isUnauthorized
            client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer ${jwt.issueAdminToken(ordinary.publicId)}").exchange().expectStatus().isForbidden
            val campaign = if (path.startsWith("/campaigns/") && !path.endsWith("app-transfer")) "&campaign=TEST" else ""
            val body = mapper.readTree(get("$path?from=2026-10-20&to=2026-10-21$campaign").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
            when (path) {
                "/campaigns/funnels/activation" -> {
                    assertThat(body["steps"].size()).isEqualTo(4)
                    assertThat(body["steps"][2]["minTurnNumber"].asInt()).isEqualTo(5)
                    assertThat(body["steps"][3]["event"].asText()).isEqualTo("signup_succeeded")
                }
                "/campaigns/reach" -> assertThat(body["chatUsers"]["all"].asLong()).isEqualTo(12)
                "/campaigns/creatives" -> {
                    assertThat(body["daily"][0]["signups"][0]["content"].isNull).isTrue()
                    assertThat(body["totals"]["signups"][0]["users"].asLong()).isEqualTo(4)
                    assertThat(body["totals"].has("deepChatUsers")).isFalse()
                }
                "/campaigns/app-transfer" -> {
                    assertThat(body["daily"][0]["bannerClickRate"].asDouble()).isEqualTo(1.0)
                    assertThat(body["totals"].has("bannerShown")).isFalse()
                }
                else -> {
                    assertThat(body["daily"][0]["newUsers"].asLong()).isEqualTo(10)
                    assertThat(body["daily"][0]["activeUsers"].asLong()).isEqualTo(9)
                    assertThat(body["totals"]["activationRate"].asDouble()).isEqualTo(0.9)
                }
            }
            for (bad in listOf("from=2026-10-20", "days=90", "days=7&from=2026-10-20&to=2026-10-21")) get("$path?$bad").expectStatus().isBadRequest
        }
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500) }
        for (path in routes) get("$path?from=2026-10-30&to=2026-10-30").expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
    }
}
@org.springframework.boot.test.context.TestConfiguration
class GrowthEndpointClock {
    @org.springframework.context.annotation.Bean
    fun growthClock(): Clock = MutableAnalyticsClock(Instant.parse("2026-10-30T03:00:00Z"))
}
