package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.concurrent.*

class AdminAnalyticsTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private val mapper = ObjectMapper()
    private val today = LocalDate.parse("2026-10-22")
    private val now = Instant.parse("2026-10-22T03:00:00Z")
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", "team", server.url("/").toString()), MutableAnalyticsClock(now))
    }
    @AfterEach fun stop() = server.shutdown()
    private fun request(from: LocalDate, to: LocalDate) = AdminChartsRequest().apply { this.from = from.toString(); this.to = to.toString() }
    private fun dispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val url = r.requestUrl!!; val from = LocalDate.parse(url.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(url.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList().map { it.toString() }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates, "series" to listOf(List(dates.size) { if (mapper.readTree(url.queryParameter("e")!!)["event_type"].asText() == "_active") 10 else 5 })))))
            }
        }
    }
    @Test fun `개요 분석은 국내와 해외 로그인 다섯 조회의 합을 반환한다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val e = r.requestUrl!!.queryParameter("e")?.let { mapper.readTree(it) }
                val p = e?.get("filters")?.toList()?.first { it["subprop_key"].asText()=="platform" }?.get("subprop_value")
                val value = when { p==null -> 0; p.size()==2 -> 13; p[0].asText()=="Web" -> 10; else -> 5 }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to listOf(today.toString()), "series" to listOf(listOf(value))))))
            }
        }
        val response = mapper.valueToTree<tools.jackson.databind.JsonNode>(fixture.service.dashboard(request(today,today)))
        assertThat(response["todayActiveUsers"].isObject).isTrue()
        for (value in listOf(response["todayActiveUsers"], response["daily"][0]["activeUsers"])) {
            assertThat(value["total"].asLong()).isEqualTo(20); assertThat(value["web"].asLong()).isEqualTo(10); assertThat(value["android"].asLong()).isEqualTo(10)
        }
        assertThat(response["daily"][0]["newUsers"]["total"].asLong()).isEqualTo(15)
        assertThat(server.requestCount).isEqualTo(5)
        repeat(5) {
            val u=server.takeRequest().requestUrl!!
            assertThat(u.encodedPath).isEqualTo("/api/2/events/segmentation"); assertThat(u.queryParameter("m")).isEqualTo("uniques")
            assertThat(u.queryParameter("e")).contains("country","South Korea","(none)","platform"); assertThat(u.queryParameter("s")).contains("userdata_cohort","team")
        }
        assertThat(fixture.budget.reserve(490)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @ParameterizedTest @ValueSource(ints = [30,90])
    fun `코호트 포함 하루 비용 10으로 30일은 300 90일은 500을 쓴다`(days: Int) {
        dispatcher()
        val value = fixture.service.dashboard(days)
        val ready = minOf(days,50)
        assertThat(value.daily).hasSize(ready); assertThat(value.pendingDates).hasSize(days-ready)
        if (ready<50) assertThat(fixture.budget.reserve(500-ready*10)).isTrue()
        assertThat(fixture.budget.reserve(1)).isFalse()
        assertThat(server.requestCount).isEqualTo(5)
    }
    @Test fun `요청 범위와 코호트 및 기본 인증을 적용하고 DB 저장 값을 재사용한다`() {
        dispatcher(); val response = fixture.service.dashboard(7)
        assertThat(response.daily).hasSize(7); assertThat(response.pendingDates).isEmpty(); assertThat(response.todayActiveUsers).isEqualTo(DashboardPlatformCounts(30, 10, 20))
        fixture.service.dashboard(request(today.minusDays(6), today)); assertThat(server.requestCount).isEqualTo(5)
        repeat(5) {
            val r = server.takeRequest(); assertThat(r.requestUrl!!.queryParameter("start")).isEqualTo("20261016")
            assertThat(r.requestUrl!!.queryParameter("s")).contains("userdata_cohort", "team")
            assertThat(r.getHeader("Authorization")).isEqualTo("Basic bW9jazptb2Nr")
        }
    }
    @Test fun `366일의 첫 조회는 최근 50일을 받고 예산 회복 뒤 다음 날짜를 이어 받는다`() {
        dispatcher(); val request = request(today.minusDays(365), today)
        val first = fixture.service.dashboard(request)
        assertThat(first.daily).hasSize(50); assertThat(first.pendingDates).hasSize(316)
        assertThat(first.daily.first().date).isEqualTo(today.minusDays(49))
        assertThat(fixture.service.dashboard(request)).isEqualTo(first); assertThat(server.requestCount).isEqualTo(5)
        fixture.clock.at = now.plusSeconds(300)
        val next = fixture.service.dashboard(request)
        assertThat(next.daily).hasSize(100); assertThat(next.pendingDates).hasSize(266)
        assertThat(server.requestCount).isEqualTo(10)
        assertThat(server.takeRequest().requestUrl!!.queryParameter("end")).isEqualTo("20261022"); repeat(4) { server.takeRequest() }
        assertThat(server.takeRequest().requestUrl!!.queryParameter("end")).isEqualTo(today.minusDays(50).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
    }
    @Test fun `오늘은 10분마다 어제는 하루 한 번만 다시 받고 확정 날짜는 받지 않는다`() {
        dispatcher(); fixture.service.dashboard(7)
        fixture.clock.at = now.plusSeconds(599); fixture.service.dashboard(7); assertThat(server.requestCount).isEqualTo(5)
        fixture.clock.at = now.plusSeconds(600); fixture.service.dashboard(7); assertThat(server.requestCount).isEqualTo(10)
        repeat(5) { server.takeRequest() }
        repeat(5) { val url = server.takeRequest().requestUrl!!; assertThat(url.queryParameter("start")).isEqualTo("20261022"); assertThat(url.queryParameter("end")).isEqualTo("20261022") }
        fixture.clock.at = now.plusSeconds(86400)
        fixture.service.dashboard(request(today.minusDays(6), today)); assertThat(server.requestCount).isEqualTo(15) // 어제와 새 오늘 active를 연속 구간 한 번, 어제 new 한 번
        fixture.service.dashboard(request(today.minusDays(6), today)); assertThat(server.requestCount).isEqualTo(15)
    }
    @Test fun `배포 뒤 새 서비스도 과거 DB 값을 재사용하고 오늘 값만 별도로 갱신한다`() {
        dispatcher(); val request = request(today.minusDays(10), today.minusDays(2))
        val first = fixture.service.dashboard(request); val calls = server.requestCount
        val reboot = AnalyticsTestFixture(fixture.properties, fixture.clock, repository = fixture.store)
        assertThat(reboot.service.dashboard(request)).isEqualTo(first); assertThat(server.requestCount).isEqualTo(calls)
        fixture.clock.at = now.plusSeconds(23 * 3600)
        assertThat(reboot.service.dashboard(request).daily).isEqualTo(first.daily); assertThat(server.requestCount).isEqualTo(calls + 3)
    }
    @Test fun `자정 뒤 실패해도 지난 날짜 응답은 유지하고 전날 오늘 값은 null이다`() {
        dispatcher(); val request = request(today, today); fixture.service.dashboard(request)
        fixture.clock.at = Instant.parse("2026-10-22T15:01:00Z")
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500) }
        val result = fixture.service.dashboard(request)
        assertThat(result.todayActiveUsers).isNull(); assertThat(result.daily).hasSize(1)
    }
    @Test fun `조회 중 자정을 지나면 전날 활성 세 값을 오늘 값으로 반환하지 않는다`() {
        fixture.clock.at = Instant.parse("2026-10-22T14:59:59Z")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                fixture.clock.at = Instant.parse("2026-10-22T15:00:01Z")
                val from = LocalDate.parse(r.requestUrl!!.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(r.requestUrl!!.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList()
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(List(dates.size) { 8 })))))
            }
        }
        val q = request(today, today)
        val first = fixture.service.dashboard(q)
        assertThat(first.todayActiveUsers).isNull(); assertThat(first.daily.single().activeUsers).isEqualTo(DashboardPlatformCounts(24, 8, 16))
        val next = fixture.service.dashboard(q)
        assertThat(next.todayActiveUsers).isEqualTo(DashboardPlatformCounts(24, 8, 16)); assertThat(server.requestCount).isEqualTo(8)
    }
    @Test fun `오늘 활성 세 조회 중 하나를 못 받으면 부분 객체 대신 null을 반환한다`() {
        val day = today.minusDays(2)
        fixture.service.dailyMetrics().forEach { metric -> fixture.store.saveDaily(metric.definition, mapOf(day to AnalyticsValue(mapper.valueToTree(4), now, now))) }
        repeat(2) { server.enqueue(MockResponse().setBody("{\"data\":{\"xValues\":[\"2026-10-22\"],\"series\":[[9]]}}")) }
        server.enqueue(MockResponse().setResponseCode(500))
        val result = fixture.service.dashboard(request(day,day))
        assertThat(result.daily).hasSize(1); assertThat(result.todayActiveUsers).isNull(); assertThat(server.requestCount).isEqualTo(3)
    }
    @ParameterizedTest @ValueSource(ints = [302, 400, 401, 403, 429, 500, 503])
    fun `실패는 60초 쿨다운 후 다시 받고 저장이 없으면 503이다`(status: Int) {
        server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive mock"))
        assertThatThrownBy { fixture.service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        fixture.clock.at = now.plusSeconds(59); assertThatThrownBy { fixture.service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        fixture.clock.at = now.plusSeconds(60); dispatcher(); assertThat(fixture.service.dashboard(7).daily).hasSize(7)
    }
    @ParameterizedTest @ValueSource(strings = ["{}", "not-json", "{\"data\":{\"xValues\":[],\"series\":[]}}", "{\"data\":{\"xValues\":[\"2026-10-22\"],\"series\":[[-1]]}}", "{\"data\":{\"xValues\":[\"2026-10-22\"],\"series\":[[0.5]]}}"])
    fun `응답 해석 실패는 0으로 간주하지 않고 비용을 반환한다`(body: String) {
        server.enqueue(MockResponse().setBody(body))
        assertThatThrownBy { fixture.service.dashboard(request(today, today)) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(fixture.budget.reserve(500)).isTrue()
    }
    @Test fun `단일 갱신 중 다른 요청은 기다리지 않고 저장 값으로 응답한다`() {
        dispatcher(); val previous = fixture.service.dashboard(7); fixture.clock.at = now.plusSeconds(600)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
            entered.countDown(); release.await(3, TimeUnit.SECONDS)
            return MockResponse().setBody("{\"data\":{\"xValues\":[\"2026-10-22\"],\"series\":[[20]]}}")
        } }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val job = pool.submit<AdminAnalyticsResponse> { fixture.service.dashboard(7) }; assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(fixture.service.dashboard(7)).isEqualTo(previous)
            release.countDown(); assertThat(job.get(2, TimeUnit.SECONDS).todayActiveUsers).isEqualTo(DashboardPlatformCounts(60, 20, 40))
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `키가 없어도 저장 값은 반환하고 잘못된 기간은 HTTP 전에 거절한다`() {
        dispatcher(); fixture.service.dashboard(7)
        val sameDefinitionWithoutKeys = AnalyticsTestFixture(AmplitudeDashboardProperties(teamCohortId = "team"), fixture.clock, repository = fixture.store)
        assertThat(sameDefinitionWithoutKeys.service.dashboard(7).daily).hasSize(7)
        val noKeys = AnalyticsTestFixture(AmplitudeDashboardProperties(), fixture.clock, repository = fixture.store)
        // 코호트 정의가 바뀌면 옛 값과 섞이지 않는다.
        assertThatThrownBy { noKeys.service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThatThrownBy { fixture.service.dashboard(request(today.minusDays(366), today)) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { fixture.service.dashboard(10) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `본문 수신도 전체 5초 예산에 포함된다`() {
        server.enqueue(MockResponse().setBody("{\"data\":{}}").setBodyDelay(6, TimeUnit.SECONDS))
        val started = System.nanoTime(); assertThatThrownBy { fixture.service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(4800), Duration.ofSeconds(7))
    }
}
