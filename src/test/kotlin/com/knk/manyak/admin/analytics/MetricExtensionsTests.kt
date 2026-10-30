package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.AdminChartsRequest
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.*
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE

class MetricExtensionsTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var definitions: MetricDefinitions
    private lateinit var service: AdminMetricsService
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private val day = LocalDate.parse("2026-10-21")
    private val reads = object : AdminMetricReadRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())) {
        override fun signupsBefore(range: AnalyticsRange, excluded: List<Long>) = 0L
        override fun signups(range: AnalyticsRange, excluded: List<Long>) = emptyList<Map<String, Any?>>()
        override fun storyCompletions(range: AnalyticsRange, excluded: List<Long>) = emptyList<Map<String, Any?>>()
    }
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z")))
        val queries = AnalyticsQueryFactory(fixture.mapper)
        definitions = MetricDefinitions(queries, fixture.mapper)
        service = AdminMetricsService(definitions, fixture.snapshots, queries, reads, AdminDashboardProperties(), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                val u = r.requestUrl!!
                val dates = AnalyticsRange(LocalDate.parse(u.queryParameter("start"), BASIC_ISO_DATE), LocalDate.parse(u.queryParameter("end"), BASIC_ISO_DATE)).dates()
                val data: Any = when {
                    u.encodedPath.endsWith("/sessions/length") -> mapOf("xValues" to listOf("0s-3s", "3s-10s"), "series" to listOf(listOf(2, 3)))
                    u.encodedPath.endsWith("/sessions/peruser") -> mapOf("xValues" to dates.map { "${it}T00:00:00" }, "series" to listOf(dates.map { 1.8 }))
                    u.encodedPath.endsWith("/sessions/average") -> mapOf("xValues" to dates.map { "${it}T00:00:00" }, "series" to listOf(dates.map { 95.5 }))
                    else -> {
                        val e = fixture.mapper.readTree(u.queryParameter("e")!!)
                        val fs = e["filters"].toList()
                        val platform = fs.first { it["subprop_key"].asText() == "platform" }["subprop_value"]
                        val device = fs.firstOrNull { it["subprop_key"].asText() == "device_type" }
                        val value = when (e["event_type"].asText()) {
                            "_new" -> if (device != null) when { device["subprop_op"].asText() == "is not" -> 7; device["subprop_value"][0].asText() == "Android" -> 85; else -> 70 }
                                else if (platform.size() == 2) 200 else if (platform[0].asText() == "Web") 180 else 0
                            AdminMetricEvents.SIGNUP -> if (platform.size() == 2) 11 else if (platform[0].asText() == "Web") 4 else 7
                            AdminMetricEvents.MESSAGE -> if (fs.any { it["subprop_key"].asText() == "input_mode" }) 0 else 120
                            AdminMetricEvents.CHAT_SUCCEEDED -> 110
                            "client_storyCreate_completed" -> 6
                            "client_generalCreate_completed" -> 2
                            AdminMetricEvents.CORE_ACTIVITY -> 40
                            else -> 0
                        }
                        mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(dates.map { if (e["event_type"].asText() == AdminMetricEvents.CORE_ACTIVITY && it == day.minusDays(1)) 0 else value }))
                    }
                }
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun range() = SegmentRangeQuery().apply { from = day.toString(); to = day.toString() }
    @Test fun `세션 수와 평균 길이는 별도 조회하고 하루 72를 예약한다`() {
        val row = service.sessions(range()).daily.single()
        assertThat(row.averageSessionSeconds.web).isEqualTo(95.5)
        assertThat(row.sessionsPerUser.web).isEqualTo(1.8)
        assertThat(row.sessionsPerUser.unknown).isNull()
        assertThat(requests).hasSize(6)
        assertThat(fixture.budget.reserve(428)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `세션 길이 분포는 같은 저장 배열로 일별 세션 수를 응답한다`() {
        val response = service.sessionLengths(range())
        assertThat(response.daily.single().sessions).isEqualTo(MetricPlatformCounts(5,5,5))
        assertThat(response.platforms).allMatch { it.totalSessions == 5L }
        assertThat(requests).hasSize(3)
    }
    @Test fun `가입 전환율은 활동 신규 방문자를 재사용하고 분모 0이면 null이다`() {
        definitions.newVisitors().forEachIndexed { i, m -> fixture.store.saveDaily(m.definition, mapOf(day to AnalyticsValue(fixture.mapper.valueToTree(listOf(200,180,0)[i]), fixture.clock.instant()))) }
        val result = AdminMetricReadService(reads, AdminDashboardProperties(), definitions, fixture.snapshots, fixture.clock).signups(AdminChartsRequest().apply { from = day.toString(); to = day.toString() })
        val row = result.daily.single()
        assertThat(row.newVisitorsByPlatform).isEqualTo(MetricPlatformCounts(200,180,0))
        val rates = requireNotNull(row.signupRate)
        assertThat(rates.all).isEqualTo(0.055)
        assertThat(rates.web).isEqualTo(4.0/180)
        assertThat(rates.android).isNull()
        assertThat(requests).hasSize(3)
        assertThat(definitions.newVisitors().map { it.definition.hash }).containsExactlyElementsOf(definitions.activity().take(6).filterIndexed { i, _ -> i % 2 == 1 }.map { it.definition.hash })
    }
    @Test fun `가입 전환율은 1을 넘는 값도 유지한다`() {
        definitions.newVisitors().forEach { m -> fixture.store.saveDaily(m.definition, mapOf(day to AnalyticsValue(fixture.mapper.valueToTree(2), fixture.clock.instant()))) }
        val row = AdminMetricReadService(reads, AdminDashboardProperties(), definitions, fixture.snapshots, fixture.clock).signups(AdminChartsRequest().apply { from = day.toString(); to = day.toString() }).daily.single()
        assertThat(row.signupRate!!.all).isEqualTo(5.5)
    }
    @Test fun `신규 방문자 조회 하나가 실패하면 가입 관련 세 필드가 함께 준비 중이다`() {
        definitions.signupPlatforms().forEach { m -> fixture.store.saveDaily(m.definition, mapOf(day to AnalyticsValue(fixture.mapper.valueToTree(11), fixture.clock.instant()))) }
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500) }
        val response = AdminMetricReadService(reads, AdminDashboardProperties(), definitions, fixture.snapshots, fixture.clock).signups(AdminChartsRequest().apply { from = day.toString(); to = day.toString() })
        assertThat(response.pendingDates).containsExactly(day)
        val row = response.daily.single()
        assertThat(row.signupsByPlatform).isNull(); assertThat(row.newVisitorsByPlatform).isNull(); assertThat(row.signupRate).isNull()
        assertThat(row.signups.total).isZero()
    }
    @Test fun `활동량은 재생성을 제외하고 핵심 활동 사용자로 나누며 분모 0이면 null이다`() {
        val response = service.quality(PlatformMetricsQuery().apply { from = day.minusDays(1).toString(); to = day.toString() })
        val activity = response.daily.last().activity
        assertThat(activity).isEqualTo(QualityActivity(110,6,2,40,3.0,0.15))
        assertThat(response.daily.first().activity.messagesPerCoreUser).isNull()
        assertThat(response.daily.first().activity.simpleCompletionsPerCoreUser).isNull()
        assertThat(requests).hasSize(15)
        val ai = requests.single { it.requestUrl!!.queryParameter("e")!!.contains(AdminMetricEvents.CHAT_SUCCEEDED) }
        assertThat(ai.requestUrl!!.queryParameter("e")).contains("is_regenerated", "false")
        assertThat(ai.requestUrl!!.queryParameter("m")).isEqualTo("totals")
    }
    @Test fun `기기 유형별 웹 신규 방문자는 광고 조건 없이 세 번 조회하고 일별 합을 낸다`() {
        val settings = CampaignSettings(listOf(CampaignDefinition("4","4차","campaign",day.minusDays(3))), mapOf("instagram" to listOf("ig"),"threads" to listOf("th"),"dcinside" to listOf("dc")))
        val campaigns = CampaignAnalyticsService(settings, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        val response = campaigns.analytics(CampaignQuery().apply { from = day.minusDays(1).toString(); to = day.toString() })
        assertThat(response.daily).allMatch { it.newVisitorsByDevice == CampaignDevices(70,85,7) }
        assertThat(response.totals.newVisitorsByDevice).isEqualTo(CampaignDevices(140,170,14))
        assertThat(requests).hasSize(16)
        val device = requests.filter { it.requestUrl!!.queryParameter("e")!!.contains("device_type") }
        assertThat(device).hasSize(3).allMatch { !it.requestUrl!!.queryParameter("e")!!.contains("utm_campaign") }
        val other = fixture.mapper.readTree(device.last().requestUrl!!.queryParameter("e")!!)["filters"].last()
        assertThat(other["subprop_op"].asText()).isEqualTo("is not")
        assertThat(other["subprop_value"].toList().map { it.asText() }).containsExactly("Apple iPhone","Apple iPad","Android")
    }
}
