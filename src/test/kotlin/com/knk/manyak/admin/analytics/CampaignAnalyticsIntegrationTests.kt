package com.knk.manyak.admin.analytics

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.format.DateTimeFormatter

@AutoConfigureRestTestClient
@org.springframework.context.annotation.Import(CampaignTestClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CampaignAnalyticsIntegrationTests {
    companion object {
        val mapper = ObjectMapper()
        val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        private val server = MockWebServer().apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("manyak.analytics.amplitude.api-key") { "mock-api" }
            registry.add("manyak.analytics.amplitude.secret-key") { "mock-secret" }
            registry.add("manyak.analytics.amplitude.team-cohort-id") { "excluded-team" }
            registry.add("manyak.analytics.amplitude.dashboard-base-url") { server.url("/").toString() }
        }
        @JvmStatic @AfterAll fun stop() { server.shutdown() }
    }
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var budget: AmplitudeBudget
    @Autowired private lateinit var clock: Clock
    @Test fun `관리자 캠페인 5개 API는 정의된 쿼리 계산 캐시 비용 예산을 적용한다`() {
        cleaner.cleanAll(); requests.clear()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                val events = url.queryParameterValues("e").map { mapper.readTree(it!!) }
                val filters = events.first()["filters"].toList()
                val ads = filters.any { it["subprop_key"].asText() == "gp:initial_utm_campaign" && it["subprop_op"].asText() == "is" }
                if (url.encodedPath.endsWith("/funnels")) return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to listOf(mapOf("cumulativeRaw" to List(events.size) { (if (ads) 80 else 100) - it * 10 })))) )
                if (url.queryParameter("g") != null || events[0].has("group_by")) {
                    val source = url.queryParameter("g") != null
                    return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf(
                        "seriesLabels" to if (source) listOf(listOf(0, "ig"), listOf(0, "(none)")) else listOf(listOf(0, "post-a"), listOf(0, "post-b")),
                        "seriesCollapsed" to if (source) listOf(listOf(mapOf("value" to 12)), listOf(mapOf("value" to 3))) else listOf(listOf(mapOf("value" to 2)), listOf(mapOf("value" to 9)))
                    ))))
                }
                val from = LocalDate.parse(url.queryParameter("start"), DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(url.queryParameter("end"), DateTimeFormatter.BASIC_ISO_DATE)
                val count = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1
                val event = events[0]["event_type"].asText()
                val values = (0 until count).map { index -> when (event) {
                    "_active" -> 100L + index * 100
                    "_new" -> {
                        val source = filters.firstOrNull { it["subprop_key"].asText() == "gp:initial_utm_source" }
                        when {
                            source != null && source["subprop_op"].asText() == "is not" -> 1L
                            source != null -> when (source["subprop_value"][0].asText()) { "ig" -> 2L; "th" -> 1L; else -> 0L }
                            ads -> 7L
                            else -> 10L
                        }
                    }
                    CampaignEvents.GOOGLE -> if (ads) 2L else 3L
                    CampaignEvents.KAKAO -> 1L
                    CampaignEvents.GENERATE -> 40L
                    CampaignEvents.COMPLETE -> 17L
                    else -> 231L
                } }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to (0 until count).map { from.plusDays(it.toLong()).toString() }, "series" to listOf(values)))))
            }
        }
        val admin = users.save(User(nickname = "관리자")); val ordinary = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        fun get(path: String) = client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer $token").exchange()
        fun body(path: String) = mapper.readTree(get(path).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        for (path in listOf("/campaigns", "/campaigns/analytics", "/campaigns/funnels/creation", "/campaigns/funnels/story-detail", "/campaigns/sources")) {
            client.get().uri("/api/v1/admin$path").exchange().expectStatus().isUnauthorized
            client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer ${jwt.issueAdminToken(ordinary.publicId)}").exchange().expectStatus().isForbidden
        }
        assertThat(body("/campaigns")["items"].size()).isEqualTo(4)
        val range = "from=2026-10-22&to=2026-10-23"
        val trends = body("/campaigns/analytics?$range")
        assertThat(trends["campaign"].asText()).isEqualTo("KR_META_WEB_ACTIVATION_COLD_202610")
        assertThat(trends["daily"].size()).isEqualTo(2)
        assertThat(trends["pendingDates"].size()).isZero()
        assertThat(trends["daily"][0]["newVisitors"]["nonAds"].asLong()).isEqualTo(3)
        assertThat(trends["daily"][0]["newVisitorsBySource"]["instagram"].asLong()).isEqualTo(2)
        assertThat(trends["daily"][0]["signups"]["all"].asLong()).isEqualTo(4)
        assertThat(trends["daily"][1]["cumulativeSignups"]["all"].asLong()).isEqualTo(8)
        assertThat(trends["totals"]["activeUsersAverage"].asDouble()).isEqualTo(150.0)
        assertThat(trends["totals"]["adsShare"].asDouble()).isEqualTo(0.7)
        assertThat(trends["totals"]["storylineRequests"].asLong()).isEqualTo(80)
        assertThat(requests).hasSize(14)
        assertThat(body("/campaigns/analytics?$range")).isEqualTo(trends)
        for ((index, request) in requests.withIndex()) {
            val url = request.requestUrl!!; val filters = mapper.readTree(url.queryParameter("e")!!)["filters"]
            assertThat(url.queryParameter("s")).isNull()
            assertThat(filters.toString()).contains("country", "South Korea", "(none)").doesNotContain("userdata_cohort")
            assertThat(filters.toString().contains("platform")).isEqualTo(index !in 7..10)
            assertThat(url.queryParameter("i")).isEqualTo("1")
            assertThat(request.getHeader("Authorization")).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("mock-api:mock-secret".toByteArray()))
            if (index in 7..10) assertThat(mapper.readTree(url.queryParameter("e")!!).toString()).contains("is_new_user", "true")
        }
        body("/campaigns/analytics?$range&campaign=TEST_2026"); assertThat(requests).hasSize(21)
        val creation = body("/campaigns/funnels/creation")
        assertThat(creation["steps"].size()).isEqualTo(6); assertThat(creation["steps"][0]["users"]["nonAds"].asLong()).isEqualTo(20)
        assertThat(requests).hasSize(23)
        for (request in requests.takeLast(2)) {
            assertThat(request.requestUrl!!.queryParameterValues("e")).hasSize(6)
            assertThat(request.requestUrl!!.queryParameter("cs")).isEqualTo("86400")
            assertThat(request.requestUrl!!.queryParameter("mode")).isEqualTo("ordered")
            assertThat(request.requestUrl!!.queryParameter("s")).isNull()
            request.requestUrl!!.queryParameterValues("e").forEach { event -> assertThat(mapper.readTree(event!!)["filters"].toString()).contains("country", "platform") }
        }
        val story = body("/campaigns/funnels/story-detail")
        assertThat(story["storyId"].asText()).isEqualTo("cca6358a-0ef3-4709-88d1-100b9faeeca8")
        val events = requests.last().requestUrl!!.queryParameterValues("e").map { mapper.readTree(it!!) }
        assertThat(events[0].toString()).contains("gp:utm_campaign", "story_id").doesNotContain("initial_utm_campaign")
        assertThat(events[6]["filters"].toList().first { it["subprop_key"].asText() == "turn_number" }["subprop_value"][0].asText()).isEqualTo("5")
        (clock as MutableAnalyticsClock).at = clock.instant().plusSeconds(300)
        val sources = body("/campaigns/sources?$range")
        assertThat(sources["sources"][0]["newVisitors"].asLong()).isEqualTo(12)
        assertThat(sources["sources"][1]["source"].isNull).isTrue()
        assertThat(sources["sources"][0].has("medium")).isFalse()
        assertThat(sources["posts"][0].has("source")).isFalse()
        assertThat(sources["posts"][0]["visitors"].asLong()).isEqualTo(9)
        assertThat(requests.last().requestUrl!!.queryParameter("e")).contains("[Amplitude] Page Viewed", "\"in\"", "community", "social")
        for (suffix in listOf("analytics", "sources", "funnels/creation", "funnels/story-detail")) {
            for (query in listOf("days=90", "days=", "from=2026-10-20", "from=2026-10-24&to=2026-10-25", "from=2026-02-30&to=2026-10-23", "days=7&$range")) get("/campaigns/$suffix?$query").expectStatus().isBadRequest
        }
        get("/campaigns/analytics?from=2025-10-21&to=2026-10-23").expectStatus().isBadRequest
        get("/campaigns/funnels/creation?from=2026-10-03&to=2026-10-23").expectStatus().isBadRequest
        get("/campaigns/funnels/creation?days=30").expectStatus().isBadRequest
        get("/campaigns/funnels/story-detail?from=2026-09-22&to=2026-10-23").expectStatus().isBadRequest
        get("/campaigns/sources?from=2026-10-13&to=2026-10-23").expectStatus().isBadRequest
        get("/campaigns/sources?days=30").expectStatus().isBadRequest
        get("/campaigns/funnels/story-detail?campaign=UNKNOWN").expectStatus().isBadRequest
        get("/campaigns/funnels/story-detail?storyId=bad").expectStatus().isBadRequest
        get("/campaigns/analytics?campaign=${"x".repeat(101)}").expectStatus().isBadRequest
        assertThat(budget.reserve(399)).isTrue() // 소스 2일 100 + 399 = 499, 하루치도 남지 않음.
        val count = requests.size
        get("/campaigns/analytics?days=7&campaign=NEW").expectStatus().isEqualTo(503)
        get("/dashboard/analytics").expectStatus().isEqualTo(503)
        assertThat(requests).hasSize(count)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
        val docs = mapper.readTree(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(docs["paths"].has("/api/v1/admin/campaigns/sources")).isTrue()
    }
}

@org.springframework.boot.test.context.TestConfiguration
class CampaignTestClock {
    @org.springframework.context.annotation.Bean
    fun campaignClock(): Clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
}
