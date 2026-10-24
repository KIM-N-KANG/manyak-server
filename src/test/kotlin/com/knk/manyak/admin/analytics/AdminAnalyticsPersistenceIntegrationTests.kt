package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.time.*

@AutoConfigureRestTestClient
@org.springframework.context.annotation.Import(StoredAnalyticsClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAnalyticsPersistenceIntegrationTests {
    companion object {
        val server = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("manyak.analytics.amplitude.api-key") { "mock" }; r.add("manyak.analytics.amplitude.secret-key") { "mock" }
            r.add("manyak.analytics.amplitude.dashboard-base-url") { server.url("/").toString() }
        }
        @JvmStatic @AfterAll fun stop() = server.shutdown()
    }
    @Autowired lateinit var http: RestTestClient
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var clock: Clock
    private val mapper = ObjectMapper()
    @Test fun `응답 값을 실제 테이블에 저장하고 겹치는 범위는 저장 날짜를 재사용한다`() {
        cleaner.cleanAll()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val url = r.requestUrl!!
                val from = LocalDate.parse(url.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(url.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList().map { it.toString() }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates, "series" to listOf(List(dates.size) { 12 })))))
            }
        }
        val admin = users.save(User(nickname = "관리자")); jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        fun get(query: String) = http.get().uri("/api/v1/admin/dashboard/analytics?$query").header("Authorization", "Bearer $token").exchange()
        get("days=7").expectStatus().isOk
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_analytics_daily_values", Long::class.java)).isEqualTo(35)
        val count = server.requestCount
        get("from=2026-10-17&to=2026-10-21").expectStatus().isOk.expectBody().jsonPath("$.pendingDates.length()").isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(count)
    }
}
@org.springframework.boot.test.context.TestConfiguration
class StoredAnalyticsClock {
    @org.springframework.context.annotation.Bean
    fun storedClock(): Clock = MutableAnalyticsClock(Instant.parse("2026-10-22T03:00:00Z"))
}
