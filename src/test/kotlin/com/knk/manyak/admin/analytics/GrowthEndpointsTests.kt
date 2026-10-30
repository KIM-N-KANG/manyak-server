package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.config.AdminDashboardProperties
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.time.*
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE

class GrowthEndpointsTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var campaigns: CampaignAnalyticsService
    private lateinit var users: AdminMetricsService
    private lateinit var definitions: MetricDefinitions
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private val day = LocalDate.parse("2026-10-21")
    private val settings = CampaignSettings(listOf(CampaignDefinition("4", "4차", "campaign", LocalDate.parse("2026-10-01"))), mapOf("instagram" to listOf("ig"), "threads" to listOf("th"), "dcinside" to listOf("dc")))
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z")))
        val q = AnalyticsQueryFactory(fixture.mapper); definitions = MetricDefinitions(q, fixture.mapper)
        campaigns = CampaignAnalyticsService(settings, fixture.snapshots, q, fixture.clock)
        users = AdminMetricsService(definitions, fixture.snapshots, q, modelCodeReads(), AdminDashboardProperties(), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                val u = r.requestUrl!!
                val dates = AnalyticsRange(LocalDate.parse(u.queryParameter("start"), BASIC_ISO_DATE), LocalDate.parse(u.queryParameter("end"), BASIC_ISO_DATE)).dates()
                val events = u.queryParameterValues("e").map { fixture.mapper.readTree(it!!) }
                val fs = events[0]["filters"].toString()
                val ads = fs.contains("initial_utm_campaign")
                val deep = fs.contains("turn_number")
                val data: Any = when {
                    u.encodedPath.endsWith("/funnels") -> listOf(mapOf("cumulativeRaw" to if (events.size == 2) listOf(20,6) else if (ads) listOf(80,25,8,2) else listOf(100,30,10,2)))
                    u.queryParameter("g") != null -> mapOf("xValues" to dates.map { it.toString() }, "seriesLabels" to listOf(listOf(0,"creative"), listOf(0,"(none)")), "series" to listOf(dates.map { 9 }, dates.map { 1 }))
                    else -> {
                        val value = when (events[0]["event_type"].asText()) { "client_appBanner_shown" -> 140; "client_appBanner_actionButton_clicked" -> 21; "_new" -> 4; else -> 900 }
                        val collapsed = if (deep) if (ads) 70 else 90 else if (ads) 250 else 300
                        mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(dates.map { value }), "seriesCollapsed" to listOf(listOf(mapOf("value" to collapsed))))
                    }
                }
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun query(days: Int = 1) = CampaignQuery().apply { from = day.minusDays(days.toLong()-1).toString(); to = day.toString() }
    @Test fun `첫 방문 활성화는 네 단계 신규 사용자 퍼널을 전체와 광고로 조회한다`() {
        val result = campaigns.activation(query(7))
        assertThat(result.steps.map { it.event }).containsExactly("_active", AdminMetricEvents.MESSAGE, AdminMetricEvents.MESSAGE, "signup_succeeded")
        assertThat(result.steps.map { it.minTurnNumber }).containsExactly(null,null,5,null)
        assertThat(result.steps[0].users).isEqualTo(CampaignSegments(100,80))
        assertThat(requests).hasSize(2).allMatch { it.requestUrl!!.queryParameter("n") == "new" && it.requestUrl!!.queryParameter("cs") == "86400" }
        assertThat(requests.last().requestUrl!!.queryParameterValues("e")).allMatch { it!!.contains("gp:initial_utm_campaign") }
        assertThat(requests.first().requestUrl!!.queryParameterValues("e")[2]).contains("greater or equal", "5")
        assertThat(fixture.budget.reserve(388)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `누적 도달은 일별 합 대신 seriesCollapsed를 쓴다`() {
        val result = campaigns.reach(query(7))
        assertThat(result.chatUsers).isEqualTo(CampaignSegments(300,250))
        assertThat(result.deepChatUsers).isEqualTo(CampaignSegments(90,70))
        assertThat(requests).hasSize(4)
        assertThat(fixture.budget.reserve(472)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `광고 소재는 묶음 값 없음을 null로 바꾸고 가입만 기간 합을 준다`() {
        val result = campaigns.creatives(query(2))
        assertThat(result.daily).hasSize(2)
        assertThat(result.totals.signups.map { it.content to it.users }).containsExactly("creative" to 18L, null to 2L)
        val json = fixture.mapper.valueToTree<tools.jackson.databind.JsonNode>(result)
        assertThat(json["totals"].has("deepChatUsers")).isFalse()
        assertThat(requests).hasSize(2).allMatch { it.requestUrl!!.queryParameter("g") == "gp:initial_utm_content" && it.requestUrl!!.queryParameter("limit") == "1000" }
        assertThat(fixture.budget.reserve(400)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `앱 유입은 배너 클릭률과 신규 사용자 소스 합을 응답한다`() {
        val result = campaigns.appTransfer(query(2))
        assertThat(result.daily.first().bannerClickRate).isEqualTo(0.15)
        assertThat(result.totals.bannerNewUsers).isEqualTo(8)
        assertThat(result.totals.newUsersBySource.map { it.source to it.users }).containsExactly("creative" to 18L, null to 2L)
        assertThat(requests).hasSize(4)
        assertThat(requests.map { it.requestUrl!!.queryParameter("e") }).noneMatch { it!!.contains("utm_campaign") }
        assertThat(fixture.budget.reserve(444)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `앱 신규 활성은 날짜별 퍼널이고 서버 단계에 플랫폼 조건을 두지 않는다`() {
        val result = users.appActivation(query(2))
        assertThat(result.daily).allMatch { it.newUsers == 20L && it.activeUsers == 6L && it.activationRate == 0.3 }
        assertThat(result.totals.newUsers).isEqualTo(40); assertThat(result.totals.activationRate).isEqualTo(0.3)
        assertThat(requests).hasSize(2).allMatch { it.requestUrl!!.queryParameter("start") == it.requestUrl!!.queryParameter("end") && it.requestUrl!!.queryParameter("n") == "new" }
        val events = requests.first().requestUrl!!.queryParameterValues("e")
        assertThat(events[0]).contains("Android", "country"); assertThat(events[1]).contains(AdminMetricEvents.CORE_ACTIVITY, "country").doesNotContain("platform")
        assertThat(fixture.budget.reserve(492)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `광고 소재 366일은 최근 10일만 먼저 받고 저장된 날짜는 재사용한다`() {
        val result = campaigns.creatives(query(366))
        assertThat(result.daily).hasSize(10); assertThat(result.pendingDates).hasSize(356)
        assertThat(requests).hasSize(2)
        assertThat(campaigns.creatives(query(366))).isEqualTo(result)
        assertThat(requests).hasSize(2)
    }
    @Test fun `앱 유입 366일은 예산 안의 최근 17일을 먼저 받는다`() {
        val result = campaigns.appTransfer(query(366))
        assertThat(result.daily).hasSize(17); assertThat(result.pendingDates).hasSize(349)
        assertThat(requests).hasSize(4)
        assertThat(fixture.budget.reserve(24)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `앱 활성 366일도 남은 두 날짜 비용만 예약하고 나머지를 준비 중으로 둔다`() {
        assertThat(fixture.budget.reserve(492)).isTrue()
        val result = users.appActivation(query(366))
        assertThat(result.daily).hasSize(2); assertThat(result.pendingDates).hasSize(364)
        assertThat(requests).hasSize(2)
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["activation", "reach"])
    fun `범위 지표는 31일 상한을 정상 조회한다`(kind: String) {
        if (kind == "activation") {
            assertThat(campaigns.activation(query(31)).steps).hasSize(4)
            assertThat(fixture.budget.reserve(4)).isTrue()
        } else {
            assertThat(campaigns.reach(query(31)).chatUsers.all).isEqualTo(300)
            assertThat(fixture.budget.reserve(376)).isTrue()
        }
        assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `빈 정상 그룹과 퍼널은 0 값과 null 비율로 준비 완료다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val u = r.requestUrl!!
                val dates = AnalyticsRange(LocalDate.parse(u.queryParameter("start"), BASIC_ISO_DATE), LocalDate.parse(u.queryParameter("end"), BASIC_ISO_DATE)).dates()
                val data: Any = when {
                    u.encodedPath.endsWith("/funnels") -> emptyList<Any>()
                    u.queryParameter("g") != null -> mapOf("xValues" to dates.map { it.toString() }, "seriesLabels" to emptyList<Any>(), "series" to emptyList<Any>())
                    else -> mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(dates.map { 0 }), "seriesCollapsed" to listOf(listOf(mapOf("value" to 0))))
                }
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
        assertThat(campaigns.activation(query()).steps).allMatch { it.users.all == 0L }
        assertThat(campaigns.reach(query()).deepChatUsers.all).isZero()
        assertThat(campaigns.creatives(query()).daily.single().signups).isEmpty()
        assertThat(campaigns.appTransfer(query()).daily.single().bannerClickRate).isNull()
        assertThat(users.appActivation(query()).totals.activationRate).isNull()
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["[20]", "[20,21]", "[-1,0]"])
    fun `앱 활성 퍼널 형식 오류는 저장하지 않고 사용 불가다`(values: String) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setBody("""{"data":[{"cumulativeRaw":$values}]}""") }
        assertThatThrownBy { users.appActivation(query()) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(fixture.store.daily(definitions.appActivation().definition, day, day)).isEmpty()
    }
    @Test fun `범위 상한과 기본 캠페인 누락은 호출 전에 거절한다`() {
        assertThatThrownBy { campaigns.activation(query(32)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { campaigns.reach(query(32)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { campaigns.creatives(query(367)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { campaigns.appTransfer(query(367)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { users.appActivation(query(367)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        val empty = CampaignAnalyticsService(CampaignSettings(), fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        assertThatThrownBy { empty.activation(CampaignQuery()) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(requests).isEmpty()
    }
}
