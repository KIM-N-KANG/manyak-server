package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito
import tools.jackson.databind.JsonNode
import java.time.*

class AnalyticsSnapshotsTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var metric: DailyAnalyticsMetric
    private val now = Instant.parse("2026-10-22T03:00:00Z")
    private val day = LocalDate.parse("2026-10-22")
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(now))
        val def = AnalyticsDefinition("stored_test", "/api/2/users", listOf("m" to "active", "i" to "1"), 1, fixture.mapper)
        metric = DailyAnalyticsMetric(def) { data, range -> range.dates().zip(AmplitudeResults.series(data, range).map { fixture.mapper.valueToTree<JsonNode>(it) }).toMap() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val from = LocalDate.parse(r.requestUrl!!.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(r.requestUrl!!.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList()
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(List(dates.size) { 7 })))))
            }
        }
    }
    @AfterEach fun stop() = server.shutdown()
    private fun number(n: Long, fetched: Instant = now, final: Instant? = null) = AnalyticsValue(fixture.mapper.valueToTree<JsonNode>(n), fetched, final)
    @Test fun `저장된 중간 날짜는 건너뛰고 최근 날짜부터 두 빈 구간만 조회한다`() {
        fixture.store.saveDaily(metric.definition, (4L..10L).associate { day.minusDays(it) to number(3, now.minusSeconds(86400)) })
        val range = AnalyticsRange(day.minusDays(13), day.minusDays(2))
        val result = fixture.snapshots.load("/test", range, listOf(metric))
        assertThat(result.readyDates()).hasSize(12); assertThat(result.pendingDates()).isEmpty()
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest().requestUrl!!; val second = server.takeRequest().requestUrl!!
        assertThat(first.queryParameter("start")).isEqualTo("20261019"); assertThat(first.queryParameter("end")).isEqualTo("20261020")
        assertThat(second.queryParameter("start")).isEqualTo("20261009"); assertThat(second.queryParameter("end")).isEqualTo("20261011")
        assertThat(result.value(metric, day.minusDays(4))!!.value.asLong()).isEqualTo(3)
    }
    @Test fun `확정일은 날짜가 끝난 뒤 받은 값만 확정하며 당일 값은 한 번 더 받는다`() {
        val settled = day.minusDays(2); val unfinished = day.minusDays(3)
        fixture.store.saveDaily(metric.definition, mapOf(settled to number(3, now.minusSeconds(86400)), unfinished to number(2, now.minusSeconds(3 * 86400))))
        fixture.snapshots.load("/test", AnalyticsRange(unfinished, settled), listOf(metric))
        assertThat(server.requestCount).isEqualTo(1)
        val stored = fixture.store.daily(metric.definition, unfinished, settled)
        assertThat(stored[settled]!!.value.asLong()).isEqualTo(3); assertThat(stored[unfinished]!!.value.asLong()).isEqualTo(7)
        assertThat(stored.values).allMatch { it.finalizedAt != null }
    }
    @Test fun `과거 범위 값은 영구 재사용하고 현재 범위는 10분마다 다시 받는다`() {
        val range = AnalyticsRange(day.minusDays(5), day.minusDays(3))
        val def = AnalyticsDefinition("range_test", "/api/2/funnels", emptyList(), 12, fixture.mapper)
        val past = RangeAnalyticsMetric(def, range) { fixture.mapper.valueToTree<JsonNode>(10L) }
        fixture.snapshots.load("/range", range, emptyList(), rangeMetrics = listOf(past))
        fixture.clock.at = now.plusSeconds(23 * 3600)
        fixture.snapshots.load("/range", range, emptyList(), rangeMetrics = listOf(past)); assertThat(server.requestCount).isEqualTo(1)
        fixture.clock.at = now
        val currentRange = AnalyticsRange(day.minusDays(1), day)
        val current = RangeAnalyticsMetric(def, currentRange) { fixture.mapper.valueToTree<JsonNode>(10L) }
        fixture.snapshots.load("/range", currentRange, emptyList(), rangeMetrics = listOf(current))
        fixture.clock.at = now.plusSeconds(600)
        fixture.snapshots.load("/range", currentRange, emptyList(), rangeMetrics = listOf(current)); assertThat(server.requestCount).isEqualTo(3)
    }
    @Test fun `범위 지표의 여러 조회는 전체 갱신 비용을 예약할 수 있어야 호출한다`() {
        val range = AnalyticsRange(day.minusDays(6), day)
        fun spec(segment: String) = RangeAnalyticsMetric(AnalyticsDefinition("funnel_" + segment, "/api/2/funnels", listOf("segment" to segment), 12, fixture.mapper), range) { fixture.mapper.valueToTree<JsonNode>(10) }
        val all = spec("all"); val ads = spec("ads")
        assertThat(fixture.budget.reserve(400)).isTrue() // 남은 100은 한 조회 84만 가능하며 전체 168은 불가능하다.
        val result = fixture.snapshots.load("/range", range, emptyList(), rangeMetrics = listOf(all, ads))
        assertThat(server.requestCount).isZero(); assertThat(result.value(all)).isNull(); assertThat(result.value(ads)).isNull()
        assertThat(fixture.budget.reserve(100)).isTrue()
    }
    @Test fun `리텐션은 31개 구간이 모두 완료되어야 고정하고 미완료는 하루 한 번만 갱신한다`() {
        fun array(complete: Boolean) = fixture.mapper.valueToTree<JsonNode>(List(31) { mapOf("count" to 5, "outof" to 20, "incomplete" to !complete) })
        val def = AnalyticsDefinition("retention_test", "/api/2/retention", emptyList(), 8, fixture.mapper)
        val retention = DailyAnalyticsMetric(def, true) { _, range -> range.dates().associateWith { array(false) } }
        fixture.store.saveDaily(def, mapOf(day.minusDays(40) to AnalyticsValue(array(true), now), day.minusDays(10) to AnalyticsValue(array(false), now)))
        fixture.snapshots.load("/retention", AnalyticsRange(day.minusDays(40), day.minusDays(40)), listOf(retention))
        fixture.snapshots.load("/retention", AnalyticsRange(day.minusDays(10), day.minusDays(10)), listOf(retention)); assertThat(server.requestCount).isZero()
        fixture.clock.at = now.plusSeconds(86400)
        fixture.snapshots.load("/retention", AnalyticsRange(day.minusDays(40), day.minusDays(40)), listOf(retention))
        fixture.snapshots.load("/retention", AnalyticsRange(day.minusDays(10), day.minusDays(10)), listOf(retention)); assertThat(server.requestCount).isEqualTo(1)
        assertThat(fixture.store.daily(def, day.minusDays(40), day.minusDays(40)).values.single().finalizedAt).isNotNull()
    }
    @Test fun `정의 JSON 객체 키 순서는 해시를 바꾸지 않고 코호트 변경은 새 키다`() {
        fun def(json: String) = AnalyticsDefinition("same", "/api/2/users", listOf("s" to json, "m" to "active"), 2, fixture.mapper)
        val first = def("[{\"prop\":\"userdata_cohort\",\"op\":\"is not\",\"values\":[\"team\"]}]")
        val same = def("[{\"values\":[\"team\"],\"op\":\"is not\",\"prop\":\"userdata_cohort\"}]")
        assertThat(first.hash).isEqualTo(same.hash)
        assertThat(first.hash).isNotEqualTo(def("[{\"prop\":\"userdata_cohort\",\"op\":\"is not\",\"values\":[\"other\"]}]").hash)
        assertThat(first.params).doesNotContain("mock")
    }
    @Test fun `저장 예외 때 받은 값으로 응답하고 다음 요청에서 다시 받는다`() {
        val date = day.minusDays(3); val rows = mapOf(date to number(7, now, now)); val spy = Mockito.spy(fixture.store)
        Mockito.doThrow(org.springframework.dao.DataAccessResourceFailureException("private SQL")).`when`(spy).saveDaily(metric.definition, rows)
        val snapshots = AnalyticsSnapshots(spy, fixture.client, fixture.budget, fixture.mapper, fixture.clock)
        assertThat(snapshots.load("/test", AnalyticsRange(date, date), listOf(metric)).value(metric, date)!!.value.asLong()).isEqualTo(7)
        assertThat(fixture.store.daily(metric.definition, date, date)).isEmpty()
        snapshots.load("/test", AnalyticsRange(date, date), listOf(metric)); assertThat(server.requestCount).isEqualTo(2)
    }
    @Test fun `예약 후 다른 태스크가 저장한 날짜는 호출 직전에 제외하고 예약분을 반환한다`() {
        val date = day.minusDays(3); val spy = Mockito.spy(fixture.store); var reads = 0
        Mockito.doAnswer { invocation ->
            if (++reads == 2) fixture.store.saveDaily(metric.definition, mapOf(date to number(8)))
            invocation.callRealMethod()
        }.`when`(spy).daily(metric.definition, date, date)
        val snapshots = AnalyticsSnapshots(spy, fixture.client, fixture.budget, fixture.mapper, fixture.clock)
        val result = snapshots.load("/test", AnalyticsRange(date, date), listOf(metric))
        assertThat(result.value(metric, date)!!.value.asLong()).isEqualTo(8); assertThat(server.requestCount).isZero()
        assertThat(fixture.budget.reserve(500)).isTrue()
    }
    @Test fun `성공 응답의 해석 오류 로그는 200 예외와 지표를 보존하고 캐시와 비용을 남기지 않는다`() {
        val logger = org.slf4j.LoggerFactory.getLogger(AnalyticsSnapshots::class.java) as ch.qos.logback.classic.Logger
        val events = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setBody("""{"data":{"private":"secret response"}}""") }
            val range = AnalyticsRange(day,day)
            val result = fixture.snapshots.load("/test",range,listOf(metric))
            assertThat(result.readyDates()).isEmpty()
            assertThat(result.progress.failure?.name).isEqualTo("parse_error")
            val failure = events.list.single { it.formattedMessage.startsWith("admin_analytics_fetch_failed") }
            assertThat(failure.formattedMessage).contains("reason=parse_error", "status=200", "exception=java.lang.IllegalArgumentException", "phase=result_decode", "metric=stored_test")
                .doesNotContain("secret response", "private", "Authorization")
            assertThat(failure.throwableProxy).isNull()
            assertThat(fixture.store.daily(metric.definition,day,day)).isEmpty()
            fixture.snapshots.load("/test",range,listOf(metric)); assertThat(server.requestCount).isEqualTo(1)
            assertThat(fixture.budget.reserve(500)).isTrue()
        } finally { logger.detachAppender(events); events.stop() }
    }

    @Test fun `갱신 로그는 호출 없이 재사용할 때 없고 수치와 안전한 오류 사유만 기록한다`() {
        val logger = org.slf4j.LoggerFactory.getLogger(AnalyticsSnapshots::class.java) as ch.qos.logback.classic.Logger
        val events = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        try {
            val range = AnalyticsRange(day, day)
            fixture.snapshots.load("/test", range, listOf(metric))
            assertThat(events.list.last().formattedMessage).contains("queries=1", "fetched_days=1", "reused_days=0", "pending_days=0", "cost=1")
            val count = events.list.size
            fixture.snapshots.load("/test", range, listOf(metric)); assertThat(events.list).hasSize(count)
            fixture.clock.at = now.plusSeconds(600)
            fixture.snapshots.load("/test", range, listOf(metric))
            assertThat(events.list.last().formattedMessage).contains("reused_days=0")
            fixture.clock.at = now.plusSeconds(1200)
            server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500).setBody("sensitive mock response") }
            fixture.snapshots.load("/test", range, listOf(metric))
            val failure = events.list.last { it.formattedMessage.startsWith("admin_analytics_fetch_failed") }
            assertThat(failure.formattedMessage).contains("reason=http_error", "status=500", "endpoint=/test").doesNotContain("mock", "sensitive")
            assertThat(failure.throwableProxy).isNull()
        } finally { logger.detachAppender(events); events.stop() }
    }

}
