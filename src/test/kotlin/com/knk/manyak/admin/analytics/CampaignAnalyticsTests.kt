package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.time.*
import java.util.UUID

class CampaignAnalyticsTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var service: CampaignAnalyticsService
    private val day = LocalDate.parse("2026-10-23")
    private val now = Instant.parse("2026-10-23T03:00:00Z")
    private val story = UUID.fromString("cca6358a-0ef3-4709-88d1-100b9faeeca8")
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private val settings = CampaignSettings(listOf(CampaignDefinition("4", "4차", "campaign", LocalDate.parse("2026-10-22"), storyId = story)),
        mapOf("instagram" to listOf("ig"), "threads" to listOf("th", "threads"), "dcinside" to listOf("dc")))
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(now))
        service = CampaignAnalyticsService(settings, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                val url = r.requestUrl!!; val events = url.queryParameterValues("e").map { fixture.mapper.readTree(it!!) }
                if (url.encodedPath.endsWith("/funnels")) {
                    val ads = events[0]["filters"].toList().any { it["subprop_key"].asText() == "gp:initial_utm_campaign" }
                    return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to listOf(mapOf("cumulativeRaw" to List(events.size) { (if (ads) 80 else 100) - it * 10 })))))
                }
                if (url.queryParameter("g") != null || events[0].has("group_by")) return MockResponse().setBody("{\"data\":{\"seriesLabels\":[[0,\"ig\"],[0,\"(none)\"]],\"seriesCollapsed\":[[{\"value\":12}],[{\"value\":3}]]}}")
                val from = LocalDate.parse(url.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(url.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList()
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(List(dates.size) { 0 })))))
            }
        }
    }
    @AfterEach fun stop() = server.shutdown()
    private fun query(days: Int) = CampaignQuery().apply { from = day.minusDays(days.toLong() - 1).toString(); to = day.toString() }
    @Test fun `간편 제작은 20일 비용 480을 예약하고 21일과 days30은 거절한다`() {
        val result = service.creation(query(20))
        assertThat(result.steps).hasSize(6); assertThat(result.steps[0].users.nonAds).isEqualTo(20)
        assertThat(fixture.budget.reserve(20)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        assertThat(server.requestCount).isEqualTo(2)
        assertThatThrownBy { service.creation(query(21)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { service.creation(CampaignQuery().apply { days = "30" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        for (r in requests) {
            assertThat(r.requestUrl!!.queryParameter("s")).isNull()
            r.requestUrl!!.queryParameterValues("e").forEach { raw ->
                val filters = fixture.mapper.readTree(raw!!)["filters"]
                assertThat(filters.toString()).contains("country", "platform", "\"user\"")
            }
        }
    }
    @Test fun `상세 진입 31일은 비용434이며 단계별 대상 스토리 캠페인과 문자열 턴을 보낸다`() {
        val q = StoryCampaignQuery().apply { from = day.minusDays(30).toString(); to = day.toString() }
        val result = service.story(q)
        assertThat(result.storyId).isEqualTo(story); assertThat(result.steps.map { it.turnNumber }).containsExactly(null, null, 1, 2, 3, 4, 5)
        assertThat(fixture.budget.reserve(66)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        val events = requests.single().requestUrl!!.queryParameterValues("e").map { fixture.mapper.readTree(it!!) }
        assertThat(events[0].toString()).contains("story_id", "gp:utm_campaign").doesNotContain("initial_utm_campaign")
        assertThat(events[1].toString()).contains("story_id").doesNotContain("utm_campaign")
        for (i in 2..6) {
            val turn = events[i]["filters"].toList().single { it["subprop_key"].asText() == "turn_number" }["subprop_value"][0]
            assertThat(turn.isString).isTrue(); assertThat(turn.asText()).isEqualTo((i - 1).toString())
        }
        q.from = day.minusDays(31).toString(); assertThatThrownBy { service.story(q) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `소스10일은 묶음 하나씩 비용500이며 매체와 게시글 소스는 응답하지 않는다`() {
        val q = AnalyticsRangeQuery().apply { from = day.minusDays(9).toString(); to = day.toString() }
        val result = service.sources(q)
        assertThat(fixture.budget.reserve(1)).isFalse(); assertThat(server.requestCount).isEqualTo(2)
        val json = fixture.mapper.valueToTree<tools.jackson.databind.JsonNode>(result)
        assertThat(json["sources"][0].has("medium")).isFalse(); assertThat(json["posts"][0].has("source")).isFalse()
        assertThat(requests[0].requestUrl!!.queryParameter("g")).isEqualTo("gp:initial_utm_source")
        assertThat(requests[0].requestUrl!!.queryParameter("g2")).isNull()
        val event = fixture.mapper.readTree(requests[1].requestUrl!!.queryParameter("e")!!)
        assertThat(event["group_by"].size()).isEqualTo(1); assertThat(event["group_by"][0]["value"].asText()).isEqualTo("utm_content")
        q.from = day.minusDays(10).toString(); assertThatThrownBy { service.sources(q) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `366일 추이는 최근35일을 먼저 저장하고 다음 창에 이어 받으며 0 비중은 null이다`() {
        val q = query(366)
        val first = service.analytics(q)
        assertThat(first.daily).hasSize(35); assertThat(first.pendingDates).hasSize(331)
        assertThat(first.totals.adsShare).isNull(); assertThat(first.totals.newVisitors.all).isZero()
        assertThat(fixture.budget.reserve(10)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        fixture.clock.at = now.plusSeconds(300)
        val next = service.analytics(q)
        assertThat(next.daily).hasSize(70); assertThat(next.pendingDates).hasSize(296)
        assertThat(server.requestCount).isEqualTo(28)
        val fresh = CampaignAnalyticsService(settings, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        assertThat(fresh.analytics(query(7)).pendingDates).isEmpty()
        assertThat(server.requestCount).isEqualTo(28)
    }
    @Test fun `캠페인 매개변수만 달라지면 공통 정의는 재사용하고 광고와 비광고 필터만 다시 받는다`() {
        service.analytics(query(2)); assertThat(server.requestCount).isEqualTo(14)
        service.analytics(query(2).apply { campaign = "CUSTOM" }); assertThat(server.requestCount).isEqualTo(21)
        val signup = requests.take(14).filter { r -> r.requestUrl!!.queryParameter("e")!!.contains("is_new_user") }
        assertThat(signup).hasSize(4); signup.forEach { assertThat(it.requestUrl!!.queryParameter("e")).doesNotContain("platform").contains("country") }
    }
    @Test fun `14개 중 실패 전에 성공한 값은 저장하고 쿨다운 후 빠진 정의만 채운다`() {
        val delegate = server.dispatcher; var count = 0
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
            if (++count == 2) return MockResponse().setResponseCode(500)
            return delegate.dispatch(r)
        } }
        val q = query(1)
        assertThatThrownBy { service.analytics(q) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThatThrownBy { service.analytics(q) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(2)
        fixture.clock.at = now.plusSeconds(60); server.dispatcher = delegate
        assertThat(service.analytics(q).pendingDates).isEmpty(); assertThat(server.requestCount).isEqualTo(15)
    }
    @Test fun `캠페인 추이도 저장된 가운데 날짜를 건너뛰고 빈 구간만 조회한다`() {
        val metrics = service.trendMetrics("campaign")
        for (metric in metrics) fixture.store.saveDaily(metric.definition, (4L..10L).associate { day.minusDays(it) to AnalyticsValue(fixture.mapper.valueToTree(0L), now.minusSeconds(86400)) })
        val q = CampaignQuery().apply { from = day.minusDays(13).toString(); to = day.minusDays(2).toString() }
        assertThat(service.analytics(q).daily).hasSize(12); assertThat(server.requestCount).isEqualTo(28)
        assertThat(requests.map { it.requestUrl!!.queryParameter("start") to it.requestUrl!!.queryParameter("end") }.toSet())
            .containsExactlyInAnyOrder("20261020" to "20261021", "20261010" to "20261012")
    }
    @Test fun `기본 캠페인과 대상 스토리가 없거나 캠페인 길이가 틀리면 호출하지 않는다`() {
        val empty = CampaignAnalyticsService(CampaignSettings(), fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        assertThatThrownBy { empty.analytics(CampaignQuery()) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { empty.story(StoryCampaignQuery().apply { campaign = "custom" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { service.analytics(CampaignQuery().apply { campaign = "x".repeat(101) }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(server.requestCount).isZero()
    }
}
