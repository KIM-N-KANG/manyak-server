package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.time.*

class ConversionRevisionTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var service: AdminMetricsService
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-30T03:00:00Z")))
        val queries = AnalyticsQueryFactory(fixture.mapper)
        service = AdminMetricsService(MetricDefinitions(queries, fixture.mapper), fixture.snapshots, queries, modelCodeReads(), com.knk.manyak.admin.config.AdminDashboardProperties(), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to listOf(mapOf("cumulativeRaw" to List(r.requestUrl!!.queryParameterValues("e").size) { 10 - it } )))))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun query() = ConversionMetricsQuery().apply { from = "2026-10-20"; to = "2026-10-20" }
    private fun events() = requests.last().requestUrl!!.queryParameterValues("e").map { fixture.mapper.readTree(it!!) }

    @Test fun `활성화 하한과 신규 사용자 기준을 적용한다`() {
        val response = service.funnel("first-visit-activation", query().apply { platform = "android" })
        assertThat(response.steps[2].minTurnNumber).isEqualTo(5)
        assertThat(response.steps[2].turnNumber).isNull()
        val turn = events()[2]["filters"].first { it["subprop_key"].asText() == "turn_number" }
        assertThat(turn["subprop_op"].asText()).isEqualTo("greater or equal")
        assertThat(turn["subprop_value"][0].asText()).isEqualTo("5")
        assertThat(requests.single().requestUrl!!.queryParameter("n")).isEqualTo("new")
    }
    @Test fun `가입과 앱 활성화의 서버 단계는 국내 조건만 적용한다`() {
        for (name in listOf("login-signup", "android-activation")) {
            val response = service.funnel(name, query())
            assertThat(response.steps[1].event).isEqualTo("signup_succeeded")
            assertThat(events().drop(1)).allMatch { it["filters"].size() == 1 && it["filters"][0]["subprop_key"].asText() == "country" }
            assertThat(events()[1]["event_type"].asText()).isEqualTo("ce:신규 가입 (구글·카카오)")
            if (name == "android-activation") {
                assertThat(response.platform).isEqualTo("android")
                assertThat(response.steps[2].event).isEqualTo("core_activity")
                assertThat(events()[2]["event_type"].asText()).isEqualTo("ce:핵심 활동")
            }
        }
    }
    @Test fun `웹 앱 전환은 30일 창과 단계별 플랫폼을 응답한다`() {
        val response = service.funnel("web-to-android", query())
        assertThat(response.platform).isNull()
        assertThat(response.steps.map { it.platform }).containsExactly("web", "android")
        assertThat(requests.single().requestUrl!!.queryParameter("cs")).isEqualTo("2592000")
        assertThat(events().map { e -> e["filters"].first { it["subprop_key"].asText() == "platform" }["subprop_value"][0].asText() }).containsExactly("Web", "Android")
        for (name in listOf("web-to-android", "android-activation")) assertThatThrownBy { service.funnel(name, query().apply { platform = "web" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `간편 제작 진입 채팅은 장르 선택 필터와 활성 기준을 사용한다`() {
        val response = service.funnel("creation-entry-chat", query().apply { platform = "android" })
        assertThat(response.steps.take(2).map { it.event }).containsExactly("client_storyCreate_viewed", "client_storyCreate_tagCategory_selected")
        assertThat(response.steps).hasSize(6)
        assertThat(events()[1]["filters"].toString()).contains("from_category", "GENRE", "direction", "forward")
        assertThat(requests.single().requestUrl!!.queryParameter("n")).isEqualTo("active")
    }
    @Test fun `게스트 한도 로그인은 커스텀 이벤트 퍼널 한 번으로 센다`() {
        val response = service.funnel("guest-limit-login", query())
        assertThat(requests).hasSize(1)
        assertThat(events().last()["event_type"].asText()).isEqualTo("ce:로그인 성공 (구글·카카오)")
        assertThat(response.steps.last().event).isEqualTo("login_succeeded")
        assertThat(response.steps.last().users).isLessThanOrEqualTo(response.steps[1].users)
    }
}
