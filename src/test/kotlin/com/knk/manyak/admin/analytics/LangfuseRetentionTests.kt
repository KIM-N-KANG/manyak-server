package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.*

class LangfuseRetentionTests {
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T15:05:00Z"))
    private val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties(), clock)
    private lateinit var server: MockWebServer
    private lateinit var snapshots: LangfuseCostSnapshots
    private var rows = emptyList<Map<String, Any?>>()
    private val repository = object : AdminMetricReadRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())) {
        override fun aiCost(range: AnalyticsRange) = rows
    }
    @BeforeEach fun start() {
        server = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val from = Instant.parse(fixture.mapper.readTree(request.requestUrl!!.queryParameter("query"))["fromTimestamp"].asText())
                return if (from < Instant.parse("2026-09-25T15:00:00Z")) MockResponse().setResponseCode(400)
                else MockResponse().setBody("{\"data\":[]}")
            }
        }
        val p = AdminLangfuseProperties(server.url("/").toString(), "public", "secret", "prod", callInterval = Duration.ofMillis(1))
        snapshots = LangfuseCostSnapshots(fixture.store, LangfuseMetricsClient(p, fixture.mapper), p, fixture.mapper, clock)
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun query(from: String, to: String) = AdminChartsRequest().apply { this.from = from; this.to = to }
    private fun response(from: String, to: String) = AdminAiCostService(repository, snapshots, AdminAiCostProperties(), clock).aiCost(query(from, to))
    private fun json(from: String, to: String) = fixture.mapper.valueToTree<tools.jackson.databind.JsonNode>(response(from, to))
    private fun save(date: String, final: Boolean = true) {
        val at = if (final) clock.instant() else LocalDate.parse(date).atTime(12, 0).toInstant(ZoneOffset.ofHours(9))
        fixture.store.saveDaily(snapshots.definition, mapOf(LocalDate.parse(date) to AnalyticsValue(fixture.mapper.readTree("[]"), at, if (final) at else null)))
    }
    @Test fun `보관 밖만이면 호출 없이 200과 만료 날짜 및 이미지 비용만 반환한다`() {
        rows = listOf(mapOf("metric_day" to LocalDate.parse("2026-07-01").toEpochDay(), "feature" to "chat_image", "model" to null, "calls" to 3L))
        val r = json("2026-07-01", "2026-07-31")
        assertThat(server.requestCount).isZero()
        assertThat(r["expiredDates"].size()).isEqualTo(31)
        assertThat(r["pendingDates"].size()).isZero()
        assertThat(r["asOf"].isNull).isTrue()
        assertThat(r["retentionDays"].asInt()).isEqualTo(30)
        assertThat(r["retentionStartDate"].asText()).isEqualTo("2026-09-26")
        assertThat(r["totals"]["costKrw"].decimalValue()).isEqualByComparingTo("144")
        assertThat(r["totals"]["costPerTurnKrw"].isNull).isTrue()
    }
    @Test fun `90일도 보관 안 최근 18일만 받고 만료 61일과 준비 중 11일을 구별한다`() {
        val r = json("2026-07-27", "2026-10-24")
        assertThat(server.requestCount).isEqualTo(6)
        assertThat(r["expiredDates"].size()).isEqualTo(61)
        assertThat(r["pendingDates"].size()).isEqualTo(11)
    }
    @Test fun `경계에 걸친 범위는 시작일까지만 조회하며 옛 비확정 저장값도 재사용한다`() {
        save("2026-09-20", false)
        val r = json("2026-09-20", "2026-09-26")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(r["expiredDates"].toList().map { it.asText() }).containsExactly("2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25")
        val q = fixture.mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        assertThat(q["fromTimestamp"].asText()).isEqualTo("2026-09-25T15:00:00Z")
    }
    @Test fun `원가 분모는 준비 중 날짜와 만료 날짜의 성공 호출을 제외한다`() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        rows = listOf("2026-10-22", "2026-10-23").map { mapOf("metric_day" to LocalDate.parse(it).toEpochDay(), "feature" to "chat_response", "model" to "x", "calls" to 10L) }
        val entries = fixture.mapper.readTree("[{\"traceName\":\"채팅 턴\",\"model\":\"x\",\"costUsd\":\"1\",\"totalTokens\":1}]")
        fixture.store.saveDaily(snapshots.definition, mapOf(LocalDate.parse("2026-10-23") to AnalyticsValue(entries, clock.instant())))
        val r = AdminAiCostService(repository, snapshots, AdminAiCostProperties(traceFeatures = mapOf("채팅 턴" to "chat_response")), clock).aiCost(query("2026-10-22", "2026-10-23"))
        assertThat(r.totals.costPerTurnKrw).isEqualByComparingTo("140")
    }
    @Test fun `배포 직후 배치는 확정 27일 중 18일 다음 실행은 9일을 채운다`() {
        snapshots.refreshFinalized()
        val start = LocalDate.parse("2026-09-26")
        val end = LocalDate.parse("2026-10-22")
        assertThat(server.requestCount).isEqualTo(6)
        assertThat(fixture.store.daily(snapshots.definition, start, end)).hasSize(18)
        snapshots.refreshFinalized()
        val values = fixture.store.daily(snapshots.definition, start, end)
        assertThat(server.requestCount).isEqualTo(9)
        assertThat(values).hasSize(27)
        assertThat(values.values).allSatisfy { assertThat(it.finalizedAt).isNotNull() }
        snapshots.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(9)
    }
    @Test fun `배치가 새로 확정된 하루만 저장하고 429 대기는 화면과 공유한다`() {
        val start = LocalDate.parse("2026-09-26")
        (0L..25L).forEach { save(start.plusDays(it).toString()) }
        snapshots.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(1)
        val q = fixture.mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        assertThat(q["fromTimestamp"].asText()).isEqualTo("2026-10-21T15:00:00Z")
        snapshots.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(1)
        clock.at = clock.at.plus(Duration.ofDays(1))
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(429).setHeader("Retry-After", "60") }
        snapshots.refreshFinalized()
        assertThat(snapshots.load(AnalyticsRange(start, LocalDate.parse("2026-10-25")))).hasSize(27)
        snapshots.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(2)
        clock.at = clock.at.plusSeconds(60)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"data\":[]}") }
        snapshots.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `외부 실패여도 요청 범위의 보관 밖 저장값이 하나라도 있으면 부분 응답한다`() {
        save("2026-09-20", false)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400) }
        val r = json("2026-09-20", "2026-09-26")
        assertThat(r["pendingDates"].toList().map { it.asText() }).containsExactly("2026-09-26")
        assertThat(r["asOf"].isNull).isFalse()
        response("2026-09-20", "2026-09-26")
        assertThat(server.requestCount).isEqualTo(1)
    }


    @Test fun `같은 시각 화면 여섯 번과 배치 여섯 번의 상한은 별도로 적용한다`() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"data\":[]}") }
        val p = AdminLangfuseProperties(server.url("/").toString(), "public", "secret", "prod", callInterval = Duration.ofMillis(1), retentionDays = 60)
        val s = LangfuseCostSnapshots(fixture.store, LangfuseMetricsClient(p, fixture.mapper), p, fixture.mapper, clock)
        assertThat(s.definition.hash).isEqualTo(snapshots.definition.hash)
        s.load(AnalyticsRange(s.retentionStartDate, LocalDate.parse("2026-10-24")))
        assertThat(server.requestCount).isEqualTo(6)
        s.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(12)
        val values = fixture.store.daily(s.definition, s.retentionStartDate, LocalDate.parse("2026-10-22"))
        assertThat(values).hasSize(34)
        assertThat(values.values).allSatisfy { assertThat(it.finalizedAt).isNotNull() }
    }
    @Test fun `보관 밖만이면 비밀 키가 없어도 저장 값과 무관하게 200이다`() {
        val p = AdminLangfuseProperties(server.url("/").toString(), "public", "", "prod")
        val unconfigured = LangfuseCostSnapshots(fixture.store, LangfuseMetricsClient(p, fixture.mapper), p, fixture.mapper, clock)
        val service = AdminAiCostService(repository, unconfigured, AdminAiCostProperties(), clock)
        val r = service.aiCost(query("2026-07-01", "2026-07-31"))
        assertThat(r.expiredDates).hasSize(31)
        assertThat(r.asOf).isNull()
        assertThat(server.requestCount).isZero()
    }
    @Test fun `개정 경계 09월25일부터28일까지는 26일부터 3일 구간만 받는다`() {
        val r = json("2026-09-25", "2026-09-28")
        assertThat(r["expiredDates"].toList().map { it.asText() }).containsExactly("2026-09-25")
        assertThat(server.requestCount).isEqualTo(1)
        val q = fixture.mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        assertThat(q["fromTimestamp"].asText()).isEqualTo("2026-09-25T15:00:00Z")
        assertThat(q["toTimestamp"].asText()).isEqualTo("2026-09-28T14:59:59.999Z")
    }
    @Test fun `과거 미확정 저장값도 비용을 재사용하고 asOf는 가장 이른 수신 시각이다`() {
        val first = Instant.parse("2026-09-01T03:00:00Z")
        val value = fixture.mapper.readTree("[{\"traceName\":\"unmapped\",\"model\":null,\"costUsd\":\"1\",\"totalTokens\":0}]")
        fixture.store.saveDaily(snapshots.definition, mapOf(LocalDate.parse("2026-09-01") to AnalyticsValue(value, first), LocalDate.parse("2026-09-05") to AnalyticsValue(value, first.plusSeconds(300))))
        val r = response("2026-09-01", "2026-09-05")
        assertThat(r.asOf).isEqualTo(first)
        assertThat(r.totals.costKrw).isEqualByComparingTo("2800")
        assertThat(r.expiredDates).containsExactly(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-04"))
        assertThat(server.requestCount).isZero()
    }
    @Test fun `턴당과 스토리당 분모 모두 pending과 expired 날짜 호출을 제외한다`() {
        val features = mapOf("채팅 턴" to "chat_response", "스토리 컴파일" to "story_completion")
        val value = fixture.mapper.valueToTree<tools.jackson.databind.JsonNode>(listOf(LangfuseCostEntry("채팅 턴", "x", "1".toBigDecimal(), 0), LangfuseCostEntry("스토리 컴파일", "x", "2".toBigDecimal(), 0)))
        fixture.store.saveDaily(snapshots.definition, mapOf(LocalDate.parse("2026-10-21") to AnalyticsValue(value, clock.instant(), clock.instant())))
        rows = listOf("2026-10-21", "2026-10-22", "2026-09-20").flatMapIndexed { index, date ->
            listOf("chat_response" to 10L, "story_completion" to 1L).map { (feature, calls) -> mapOf("metric_day" to LocalDate.parse(date).toEpochDay(), "feature" to feature, "model" to "x", "calls" to calls * (index + 1)) }
        }
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500) }
        val r = AdminAiCostService(repository, snapshots, AdminAiCostProperties(traceFeatures = features), clock).aiCost(query("2026-09-20", "2026-10-22"))
        assertThat(r.totals.costPerTurnKrw).isEqualByComparingTo("140")
        assertThat(r.totals.costPerStoryKrw).isEqualByComparingTo("2800")
    }
    @Test fun `400 쿨다운 동안 화면과 배치 모두 건너뛰며 과거만인 범위는 정상이다`() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400) }
        assertThatThrownBy { response("2026-10-24", "2026-10-24") }.isInstanceOf(AdminAiCostUnavailable::class.java)
        snapshots.refreshFinalized()
        assertThatThrownBy { response("2026-10-24", "2026-10-24") }.isInstanceOf(AdminAiCostUnavailable::class.java)
        assertThat(response("2026-07-01", "2026-07-31").expiredDates).hasSize(31)
        assertThat(server.requestCount).isEqualTo(1)
        clock.at = clock.at.plusSeconds(60)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"data\":[]}") }
        snapshots.refreshFinalized()
        assertThat(server.requestCount).isEqualTo(7)
        assertThat(response("2026-10-24", "2026-10-24").pendingDates).isEmpty()
        assertThat(server.requestCount).isEqualTo(8)
    }

    @Test fun `배치 호출 중 화면은 중복 호출하지 않고 완료 뒤에도 일초 간격을 공유한다`() {
        val start = LocalDate.parse("2026-09-26")
        (0L..25L).forEach { save(start.plusDays(it).toString()) }
        val p = AdminLangfuseProperties(server.url("/").toString(), "public", "secret", "prod", callInterval = Duration.ofSeconds(1))
        val s = LangfuseCostSnapshots(fixture.store, LangfuseMetricsClient(p, fixture.mapper), p, fixture.mapper, clock)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val times = java.util.concurrent.CopyOnWriteArrayList<Long>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                times.add(System.nanoTime()); entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return MockResponse().setBody("{\"data\":[]}")
            }
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val batch = pool.submit { s.refreshFinalized() }
            assertThat(entered.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            val today = AnalyticsRange(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"))
            assertThat(s.load(today)).isEmpty()
            assertThat(server.requestCount).isEqualTo(1)
            release.countDown(); batch.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(s.load(today)).hasSize(1)
            assertThat(times[1] - times[0]).isGreaterThanOrEqualTo(Duration.ofSeconds(1).toNanos())
        } finally { release.countDown(); pool.shutdownNow() }
    }

}
