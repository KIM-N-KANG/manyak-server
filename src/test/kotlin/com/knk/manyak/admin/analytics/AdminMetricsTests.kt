package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.format.DateTimeFormatter

class AdminMetricsTests {
    private val mapper = ObjectMapper()
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var service: AdminMetricsService
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private var broken = false
    @BeforeEach fun setup() {
        server = MockWebServer(); server.start()
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z")))
        service = AdminMetricsService(MetricDefinitions(AnalyticsQueryFactory(mapper), mapper), fixture.snapshots, AnalyticsQueryFactory(mapper), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                if (broken) return MockResponse().setResponseCode(500)
                val url = r.requestUrl!!; val from = LocalDate.parse(url.queryParameter("start"), DateTimeFormatter.BASIC_ISO_DATE); val to = LocalDate.parse(url.queryParameter("end"), DateTimeFormatter.BASIC_ISO_DATE)
                val range = AnalyticsRange(from, to); val path = url.encodedPath
                val data: Any = when {
                    path.endsWith("/retention") -> mapOf("series" to listOf(mapOf("values" to range.dates().associate { date -> date.format(DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)) to List(10) { i -> mapOf("count" to if (i==0) 20 else if (date.dayOfMonth==19) 5 else 10, "outof" to 20, "incomplete" to (i==7)) } })))
                    path.endsWith("/funnels") -> { val events = url.queryParameterValues("e"); val kakao = events.last()!!.contains(AdminMetricEvents.KAKAO); listOf(mapOf("cumulativeRaw" to if (events.size==3) listOf(10,6,if (kakao) 2 else 5) else List(events.size) { 20-it })) }
                    path.endsWith("/users") -> mapOf("series" to listOf(listOf(73)), "xValues" to listOf(from.toString()))
                    else -> {
                        val event = url.queryParameter("e")?.let { mapper.readTree(it) }; val filters = event?.get("filters")?.toList().orEmpty()
                        val platform = filters.firstOrNull { it["subprop_key"].asText()=="platform" }?.get("subprop_value")?.get(0)?.asText()
                        val name = event?.get("event_type")?.asText()
                        val value: Number = when {
                            path.endsWith("/average") -> 123.75
                            url.queryParameter("m")=="average" -> 4.5
                            name=="_active" -> when(platform) { "Web" -> 10; "Android" -> 3; "(none)" -> 2; else -> 13 }
                            name==AdminMetricEvents.MESSAGE -> if (filters.any { it["subprop_key"].asText()=="input_mode" }) 0 else 2
                            name=="client_chat_streamError_shown" -> 3
                            else -> 1
                        }
                        mapOf("xValues" to range.dates().map { it.toString() }, "series" to listOf(List(range.days) { value }), "seriesCollapsed" to listOf(99999))
                    }
                }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun range(from: String, to: String) = AnalyticsRangeQuery().apply { this.from=from; this.to=to }
    @Test fun `활동은 전체와 플랫폼을 따로 조회하고 MAU를 우선 예약한다`() {
        val value = service.activity(range("2026-10-15", "2026-10-21"))
        assertThat(value.monthlyActiveUsers).isEqualTo(73)
        assertThat(value.daily.first().activeUsers).isEqualTo(PlatformCounts(13,10,3,2))
        assertThat(value.daily.first().messagesPerUser).isEqualTo(PlatformDecimals(null,4.5,4.5))
        assertThat(value.daily.last().cumulativeNewVisitors).isEqualTo(PlatformCounts(7,7,7,7))
        assertThat(requests).hasSize(11)
        val first = requests.first().requestUrl!!
        assertThat(first.encodedPath).isEqualTo("/api/2/users"); assertThat(first.queryParameter("start")).isEqualTo("20260922"); assertThat(first.queryParameter("end")).isEqualTo("20261021")
        assertThat(first.queryParameter("s")).contains("country"); assertThat(first.queryParameter("i")).isEqualTo("30")
        requests.drop(1).forEach { assertThat(it.requestUrl!!.queryParameter("s")).isNull(); assertThat(it.requestUrl!!.queryParameter("g")).isNull() }
        assertThat(fixture.budget.reserve(370)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        val count=requests.size; service.activity(range("2026-10-15","2026-10-21")); assertThat(requests).hasSize(count)
    }
    @Test fun `활동 366일은 MAU 60을 먼저 받고 최근 44일을 준비한다`() {
        val value = service.activity(range("2025-10-23","2026-10-23"))
        assertThat(value.daily).hasSize(44); assertThat(value.pendingDates).hasSize(322); assertThat(value.monthlyActiveUsers).isEqualTo(73)
        assertThat(fixture.budget.reserve(1)).isFalse()
        assertThatThrownBy { service.activity(range("2025-10-22","2026-10-23")) }.hasMessageContaining("기간")
    }
    @Test fun `리텐션은 인덱스1과 가중 평균을 쓰고 incomplete와 미종료일은 null이다`() {
        val value = service.retention(range("2026-10-19","2026-10-22"))
        assertThat(value.platforms.map { it.platform }).containsExactly("all","web","android")
        val all=value.platforms.first(); assertThat(all.cohorts.first().d1).isEqualTo(0.25); assertThat(all.cohorts.last().d1).isNull()
        assertThat(all.cohorts.first().d7).isNull(); assertThat(all.cohorts.first().d30).isNull()
        assertThat(all.summary.d1).isEqualTo(25.0/60)
        assertThat(requests).hasSize(3)
        requests.forEach { val u=it.requestUrl!!; assertThat(u.queryParameter("rm")).isNull(); assertThat(u.queryParameter("s")).isNull(); assertThat(u.queryParameter("se")).contains("_new","country"); assertThat(u.queryParameter("re")).contains("_active","country") }
        assertThat(fixture.budget.reserve(404)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        service.retention(range("2026-10-19","2026-10-22")); assertThat(requests).hasSize(3)
        assertThatThrownBy { service.retention(AnalyticsRangeQuery().apply { days="30" }) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { service.retention(range("2026-10-22","2026-10-23")) }.isInstanceOf(RuntimeException::class.java)
    }
    @Test fun `세션은 평균 길이만 세 조건으로 받고 저장된 날은 다시 받지 않는다`() {
        val value=service.sessions(AnalyticsRangeQuery())
        assertThat(value.daily).hasSize(14); assertThat(value.daily.first().averageSessionSeconds).isEqualTo(PlatformDecimals(123.75,123.75,123.75))
        assertThat(requests).hasSize(3); requests.forEach { assertThat(it.requestUrl!!.encodedPath).isEqualTo("/api/2/sessions/average"); assertThat(it.requestUrl!!.queryParameter("s")).contains("country") }
        assertThat(mapper.readTree(requests[0].requestUrl!!.queryParameter("s")!!).size()).isEqualTo(1)
        assertThat(mapper.readTree(requests[1].requestUrl!!.queryParameter("s")!!).size()).isEqualTo(2)
        assertThat(fixture.budget.reserve(52)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `세션 60일은 최근15일 뒤 5분후 다음15일을 받는다`() {
        val q=range("2026-08-25","2026-10-23")
        val a=service.sessions(q); assertThat(a.daily).hasSize(15); assertThat(a.pendingDates).hasSize(45)
        val count=requests.size; service.sessions(q); assertThat(requests).hasSize(count)
        fixture.clock.at=fixture.clock.at.plusSeconds(300)
        val b=service.sessions(q); assertThat(b.daily).hasSize(30); assertThat(b.pendingDates).hasSize(30)
    }
    @Test fun `기능 지표는 입력방식을 따로 받고 단순 오류율 1초과를 보존한다`() {
        val q=PlatformMetricsQuery().apply { from="2026-10-20"; to="2026-10-21"; platform="web" }
        val value=service.quality(q)
        assertThat(value.daily.first().streamErrorRate).isEqualTo(1.5); assertThat(value.daily.first().messagesByInputMode["unknown"]).isEqualTo(2)
        assertThat(value.daily.first().users.keys).containsExactly("chatLoadError","storyListLoadError","storyCompleteError","creditShortage","creditChargeViewed","attendanceClicked","inviteSucceeded")
        assertThat(requests).hasSize(12)
        requests.forEach { val u=it.requestUrl!!; assertThat(u.queryParameter("e")).contains("country","platform","Web"); assertThat(u.queryParameter("s")).isNull(); assertThat(u.queryParameter("g")).isNull() }
        assertThat(requests.count { it.requestUrl!!.queryParameter("e")!!.contains("input_mode") }).isEqualTo(3)
        assertThatThrownBy { service.quality(PlatformMetricsQuery().apply { platform="unknown" }) }.isInstanceOf(RuntimeException::class.java)
    }
    @Test fun `퍼널의 플랫폼 스토리와 턴 조건 및 중복 로그인 합을 보존한다`() {
        val q=ConversionMetricsQuery().apply { from="2026-10-21"; to="2026-10-21" }
        val login=service.funnel("guest-limit-login",q)
        assertThat(login.steps[1].users).isEqualTo(6); assertThat(login.steps[2].users).isEqualTo(7); assertThat(login.steps[2].event).isEqualTo("login_succeeded")
        val id=java.util.UUID.randomUUID(); q.storyId=id; q.platform="android"
        val story=service.funnel("story-detail-chat",q)
        assertThat(story.steps.last().turnNumber).isEqualTo(5)
        val events=requests.last().requestUrl!!.queryParameterValues("e").map { mapper.readTree(it!!) }
        assertThat(events[0].toString()).contains("story_id",id.toString(),"Android"); assertThat(events[1].toString()).contains("story_id")
        assertThat(events[6].toString()).contains("turn_number","5").doesNotContain("campaign")
        assertThatThrownBy { service.funnel("web-first-visit",q) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { service.funnel("unknown",ConversionMetricsQuery()) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { service.funnel("story-creation",q) }.isInstanceOf(RuntimeException::class.java)
    }
    @Test fun `새 세션 조회 실패는 저장소가 없으면 사용 불가이고 비용은 반환된다`() {
        broken=true
        assertThatThrownBy { service.sessions(AnalyticsRangeQuery()) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(requests).hasSize(1); service.runCatching { sessions(AnalyticsRangeQuery()) }; assertThat(requests).hasSize(1)
        assertThat(fixture.budget.reserve(500)).isTrue()
    }
    @Test fun `서로 다른 지표도 공용 예산을 나눠 쓰고 준비된 날짜만 반환한다`() {
        service.activity(range("2026-10-15","2026-10-21")) // MAU 60 + 일별 70
        val result=service.quality(PlatformMetricsQuery().apply { from="2026-09-21"; to="2026-10-21" })
        assertThat(result.daily).hasSize(30); assertThat(result.pendingDates).containsExactly(LocalDate.parse("2026-09-21"))
        assertThat(fixture.budget.reserve(10)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `MAU 실패도 저장된 일별 활동은 반환하고 MAU만 null이다`() {
        val date=LocalDate.parse("2026-10-21")
        val definitions=MetricDefinitions(AnalyticsQueryFactory(mapper),mapper)
        definitions.activity().forEach { fixture.store.saveDaily(it.definition,mapOf(date to AnalyticsValue(mapper.valueToTree(2),fixture.clock.instant(),fixture.clock.instant()))) }
        broken=true
        val result=service.activity(range(date.toString(),date.toString()))
        assertThat(result.daily).hasSize(1); assertThat(result.monthlyActiveUsers).isNull(); assertThat(result.pendingDates).isEmpty()
        assertThat(requests).hasSize(1)
    }

    @Test fun `리텐션과 기능 지표의 366일 첫 조회도 비용 안에서 부분 응답한다`() {
        val retention=service.retention(range("2025-10-22","2026-10-22"))
        assertThat(retention.platforms.first().cohorts).hasSize(20); assertThat(retention.pendingDates).hasSize(346)
        fixture.clock.at=fixture.clock.at.plusSeconds(300)
        val quality=service.quality(PlatformMetricsQuery().apply { from="2025-10-23"; to="2026-10-23" })
        assertThat(quality.daily).hasSize(41); assertThat(quality.pendingDates).hasSize(325)
        assertThat(fixture.budget.reserve(8)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }

}
