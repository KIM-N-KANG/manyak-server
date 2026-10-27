package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*
import java.time.format.DateTimeFormatter
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.AfterEach
import tools.jackson.databind.JsonNode
import java.util.concurrent.CopyOnWriteArrayList

class AnalyticsBatchTests {
    private val server = MockWebServer().apply { start() }
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-22T18:00:00Z")) // KST 10/23 03:00
    private val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock)
    private val day = LocalDate.parse("2026-10-23")
    private val requested = CopyOnWriteArrayList<AnalyticsRange>()
    init {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val range = AnalyticsRange(LocalDate.parse(url.queryParameter("start"), DateTimeFormatter.BASIC_ISO_DATE), LocalDate.parse(url.queryParameter("end"), DateTimeFormatter.BASIC_ISO_DATE))
            requested.add(range)
            return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to range.dates().map { it.toString() }, "series" to listOf(List(range.days) { 1 })))))
        } }
    }
    @AfterEach fun close() { server.close() }
    private fun metric(name: String = "batch", cost: Int = 1, retention: Boolean = false) = DailyAnalyticsMetric(
        AnalyticsDefinition(name, "/api/2/events/segmentation", listOf("test" to name), cost, fixture.mapper), retention) { data, range ->
        AmplitudeResults.series(data, range).mapIndexed { i, n -> range.dates()[i] to fixture.mapper.valueToTree<JsonNode>(n) }.toMap()
    }
    private fun runner(metrics: List<DailyAnalyticsMetric>, start: LocalDate = day.minusDays(3), limit: Int = 300) = AnalyticsBatchRunner(
        AnalyticsBatchCatalog { metrics.map { AnalyticsBatchMetric(it, start) } }, fixture.snapshots, AnalyticsBatchPolicy(backfillStart = start, budgetCost = limit), clock)
    private fun save(metric: DailyAnalyticsMetric, range: AnalyticsRange, finalized: Boolean = true) {
        fixture.store.saveDaily(metric.definition, range.dates().associateWith { AnalyticsValue(fixture.mapper.valueToTree<JsonNode>(1), clock.instant(), if (finalized) clock.instant() else null) })
    }
    @Test fun `03시부터 어제를 채우고 다음 슬롯은 확정일 백필 마지막 다음은 조용히 끝난다`() {
        val m = metric(); val runner = runner(listOf(m))
        clock.at = Instant.parse("2026-10-22T17:55:00Z")
        // 확정일이 이미 있으면 02:55에는 새벽 조회를 하지 않는다.
        save(m, AnalyticsRange(day.minusDays(3), day.minusDays(2)))
        assertThat(runner.run()).isNull(); assertThat(server.requestCount).isZero()
        clock.at = Instant.parse("2026-10-22T18:00:00Z")
        assertThat(runner.run()).isEqualTo(AnalyticsBatchResult("nightly", "done", keys = 1, fetchedDays = 1, cost = 1))
        assertThat(requested).containsExactly(AnalyticsRange(day.minusDays(1), day.minusDays(1)))
        assertThat(runner.run()).isNull()
    }
    @Test fun `새벽이 끝난 다음 실행에서만 백필하고 종료 후에는 호출하지 않는다`() {
        val m = metric(); val runner = runner(listOf(m))
        assertThat(runner.run()!!.phase).isEqualTo("nightly")
        clock.at = clock.at.plusSeconds(300)
        assertThat(runner.run()).isEqualTo(AnalyticsBatchResult("backfill", "done", keys = 1, fetchedDays = 2, cost = 2))
        assertThat(requested).containsExactly(AnalyticsRange(day.minusDays(1), day.minusDays(1)), AnalyticsRange(day.minusDays(3), day.minusDays(2)))
        assertThat(runner.run()).isNull(); assertThat(server.requestCount).isEqualTo(2)
    }
    @Test fun `새벽 예산 300까지만 쓰고 5분 뒤 저장소의 남은 키를 이어 받는다`() {
        val ms = (1..4).map { metric("m$it", 100) }; val runner = runner(ms)
        assertThat(runner.run()).isEqualTo(AnalyticsBatchResult("nightly", "partial", "budget_share", 4, 3, 1, 300))
        assertThat(server.requestCount).isEqualTo(3)
        clock.at = clock.at.plusSeconds(300)
        assertThat(runner.run()).isEqualTo(AnalyticsBatchResult("nightly", "done", keys = 1, fetchedDays = 1, cost = 100))
        assertThat(server.requestCount).isEqualTo(4)
    }
    @Test fun `백필은 7월1일부터 빈 날짜만 최근 연속 구간 순서로 받는다`() {
        clock.at = Instant.parse("2026-10-22T17:55:00Z")
        val m = metric(); val start = LocalDate.parse("2026-07-01")
        save(m, AnalyticsRange(LocalDate.parse("2026-08-01"), day.minusDays(2)))
        save(m, AnalyticsRange(LocalDate.parse("2026-07-20"), LocalDate.parse("2026-07-20")))
        val result = runner(listOf(m), start).run()!!
        assertThat(result.result).isEqualTo("done"); assertThat(result.fetchedDays).isEqualTo(30)
        assertThat(requested).containsExactly(AnalyticsRange(LocalDate.parse("2026-07-21"), LocalDate.parse("2026-07-31")), AnalyticsRange(start, LocalDate.parse("2026-07-19")))
        assertThat(fixture.snapshots.missingDates(m, AnalyticsRange(start, day.minusDays(2)))).isEmpty()
    }
    @Test fun `리텐션은 최근33일 미확정 코호트만 받고 오늘 받은 불완전 값도 다시 받지 않는다`() {
        val m = metric(retention = true); val runner = runner(listOf(m), day.minusDays(40))
        save(m, AnalyticsRange(day.minusDays(10), day.minusDays(10)))
        save(m, AnalyticsRange(day.minusDays(5), day.minusDays(5)), false)
        val result = runner.run()!!
        assertThat(result.phase).isEqualTo("nightly"); assertThat(result.fetchedDays).isEqualTo(31)
        assertThat(requested.flatMap { it.dates() }).doesNotContain(day, day.minusDays(34), day.minusDays(10), day.minusDays(5))
        assertThat(fixture.snapshots.missingDates(m, AnalyticsRange(day.minusDays(33), day.minusDays(1)))).isEmpty()
    }
    @Test fun `새벽은 D-33까지 받고 잘못 확정된 D-34는 다음 초기 백필에서 받는다`() {
        val complete = fixture.mapper.valueToTree<JsonNode>(List(32) { i -> mapOf("count" to if (i == 0) 20 else 5, "outof" to 20, "incomplete" to false) })
        val invalid = fixture.mapper.valueToTree<JsonNode>(List(31) { i -> mapOf("count" to if (i == 0) 20 else 5, "outof" to 20, "incomplete" to false) })
        val m = DailyAnalyticsMetric(metric().definition, true) { _, range -> range.dates().associateWith { complete } }
        fixture.store.saveDaily(m.definition, AnalyticsRange(day.minusDays(32), day.minusDays(1)).dates().associateWith { AnalyticsValue(complete, clock.instant(), clock.instant()) })
        fixture.store.saveDaily(m.definition, listOf(33L, 34L).associate { day.minusDays(it) to AnalyticsValue(invalid, clock.instant().minusSeconds(86400), clock.instant()) })
        val runner = runner(listOf(m), day.minusDays(34))
        assertThat(runner.run()!!.phase).isEqualTo("nightly")
        assertThat(requested).containsExactly(AnalyticsRange(day.minusDays(33), day.minusDays(33)))
        clock.at = clock.at.plusSeconds(300)
        assertThat(runner.run()!!.phase).isEqualTo("backfill")
        assertThat(requested).containsExactly(AnalyticsRange(day.minusDays(33), day.minusDays(33)), AnalyticsRange(day.minusDays(34), day.minusDays(34)))
        assertThat(runner.run()).isNull()
    }

    @Test fun `500은 다음 키를 계속 처리하고 429는 그 실행을 끝내며 실패 비용은 반환한다`() {
        val ms = listOf(metric("first"), metric("second")); val runner = runner(ms)
        // enqueue 대신 응답을 순서대로 주는 dispatcher
        var calls = 0
        val success = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse =
            if (++calls == 1) MockResponse().setResponseCode(500) else success.dispatch(r) }
        val first = runner.run()!!
        assertThat(first.result).isEqualTo("partial"); assertThat(first.reason).isEqualTo("http_error"); assertThat(first.cost).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(2)
        runner.run(); assertThat(server.requestCount).isEqualTo(2) // 키 쿨다운
        clock.at = clock.at.plusSeconds(60)
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(429) }
        val limited = runner.run()!!
        assertThat(limited.reason).isEqualTo("rate_limited"); assertThat(server.requestCount).isEqualTo(3)
        assertThat(fixture.budget.reserve(499)).isTrue()
    }
    @Test fun `카탈로그는 모든 플랫폼과 진행 캠페인의 일별 키만 화면 정의 그대로 쓴다`() {
        val defs = MetricDefinitions(AnalyticsQueryFactory(fixture.mapper), fixture.mapper)
        val campaigns = CampaignSettings(listOf(
            CampaignDefinition("ongoing", "진행", "ads", day.minusDays(1)),
            CampaignDefinition("ended", "종료", "past", day.minusDays(9), day.minusDays(2))), mapOf("instagram" to listOf("ig"), "threads" to listOf("th", "threads"), "dcinside" to listOf("dc")))
        val service = CampaignAnalyticsService(campaigns, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), clock)
        val policy = AnalyticsBatchPolicy()
        val entries = DefaultAnalyticsBatchCatalog(fixture.service, defs, service, campaigns, policy, modelCodeReads()).metrics()
        val expected = fixture.service.dailyMetrics() + defs.activity() + defs.sessions() + listOf("all", "web", "android").flatMap { defs.quality(it) } + defs.retention() +
            defs.signupPlatforms() + defs.turnReach("all") + defs.chatOptions("all", listOf("AURORA", "LITE"))
        assertThat(entries.map { it.key }).containsAll(expected.map { it.definition.metric + ":" + it.definition.hash })
        assertThat(entries.map { it.metric.definition.path }).doesNotContain("/api/2/users", "/api/2/funnels")
        val common = expected.map { it.definition.metric + ":" + it.definition.hash }.toSet()
        assertThat(entries.filter { it.key !in common }).allMatch { it.start == day.minusDays(1) }
        val ended = service.trendMetrics("past").map { it.definition.hash }.toSet() - service.trendMetrics("ads").map { it.definition.hash }.toSet()
        assertThat(entries.map { it.metric.definition.hash }).doesNotContainAnyElementsOf(ended)
        assertThat(entries.map { it.key }).doesNotHaveDuplicates()
        // segment guest, member는 화면 요청에서만 받는다.
        val segmented = listOf("guest", "member").flatMap { defs.turnReach(it) + defs.chatOptions(it, listOf("AURORA", "LITE")) }.map { it.definition.hash }
        assertThat(entries.map { it.metric.definition.hash }).doesNotContainAnyElementsOf(segmented)
    }

    @Test fun `배치 300점은 전체 500점 안의 몫이고 실패 예약은 양쪽에서 반환한다`() {
        val clock = MutableAnalyticsClock(Instant.parse("2026-10-22T18:00:00Z"))
        val budget = AmplitudeBudget(clock)
        val (ticket, selected) = budget.plan(backgroundLimit = 300) { minOf(it, 400).let { n -> n to n } }
        assertThat(selected).isEqualTo(300)
        ticket.succeeded(100); ticket.close()
        budget.plan(backgroundLimit = 300) { assertThat(it).isEqualTo(200); 0 to Unit }.first.close()
        assertThat(budget.reserve(400)).isTrue()
        budget.plan(backgroundLimit = 300) { assertThat(it).isZero(); 0 to Unit }.first.close()
        clock.at = clock.at.plusSeconds(300)
        assertThat(budget.reserve(400)).isTrue()
        budget.plan(backgroundLimit = 300) { assertThat(it).isEqualTo(100); 0 to Unit }.first.close()
    }
    @Test fun `어제 받은 값은 이틀 전이 되면 외부 조회 없이 DB에서 확정한다`() {
        val m = metric(); val past = day.minusDays(2)
        fixture.store.saveDaily(m.definition, mapOf(past to AnalyticsValue(fixture.mapper.valueToTree<JsonNode>(1), clock.instant().minusSeconds(86400))))
        assertThat(fixture.snapshots.missingDates(m, AnalyticsRange(past, past))).isEmpty()
        assertThat(fixture.store.daily(m.definition, past, past)[past]!!.finalizedAt).isNotNull()
        assertThat(server.requestCount).isZero()
    }
    @Test fun `여러 배치 예약의 합 300과 화면을 합친 500을 원자적으로 지킨다`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val amounts = (1..8).map { pool.submit<Int> {
                val (ticket, n) = fixture.budget.plan(backgroundLimit = 300) { available -> minOf(100, available).let { it to it } }
                ticket.use { it.succeeded(n) }; n
            } }.map { it.get(2, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(amounts.sum()).isEqualTo(300)
            assertThat(fixture.budget.reserve(200)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        } finally { pool.shutdownNow() }
    }
    @Test fun `실제 기본 지표 정의 전체를 목 응답으로 받아 네 슬롯 안에 새벽 작업을 완료한다`() {
        val queries = AnalyticsQueryFactory(fixture.mapper); val defs = MetricDefinitions(queries, fixture.mapper)
        val settings = CampaignSettings(listOf(CampaignDefinition("campaign-key", "진행", "actual-campaign", day.minusDays(1))),
            mapOf("instagram" to listOf("ig"), "threads" to listOf("th", "threads"), "dcinside" to listOf("dc")))
        val policy = AnalyticsBatchPolicy()
        val dashboard = AdminAnalyticsService(fixture.snapshots, AmplitudeDashboardProperties(teamCohortId = "team"), fixture.mapper, clock)
        val catalog = DefaultAnalyticsBatchCatalog(dashboard, defs, CampaignAnalyticsService(settings, fixture.snapshots, queries, clock), settings, policy, modelCodeReads())
        val runner = AnalyticsBatchRunner(catalog, fixture.snapshots, policy, clock)
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
            if (r.requestUrl!!.encodedPath != "/api/2/retention") return original.dispatch(r)
            val from = LocalDate.parse(r.requestUrl!!.queryParameter("start"), DateTimeFormatter.BASIC_ISO_DATE)
            val to = LocalDate.parse(r.requestUrl!!.queryParameter("end"), DateTimeFormatter.BASIC_ISO_DATE)
            val points = List(32) { i -> mapOf("count" to if (i == 0) 2 else 1, "outof" to 2, "incomplete" to (i == 31)) }
            return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("series" to listOf(mapOf("values" to AnalyticsRange(from, to).dates().associate { it.toString() to points }))))))
        } }
        val results = (1..4).map { val result = runner.run()!!; clock.at = clock.at.plusSeconds(300); result }
        assertThat(results.map { it.phase }).containsOnly("nightly")
        assertThat(results.take(3).map { it.reason }).containsOnly("budget_share")
        assertThat(results.last().result).isEqualTo("done")
        assertThat(results).allMatch { it.cost <= 300 }
        assertThat(results.sumOf { it.cost }).isEqualTo(963)
        catalog.metrics().forEach { entry ->
            val range = AnalyticsRange(maxOf(entry.start, day.minusDays(if (entry.metric.retention) 33 else 1)), day.minusDays(1))
            assertThat(fixture.snapshots.missingDates(entry.metric, range)).isEmpty()
        }
        val requests = (0 until server.requestCount).map { server.takeRequest().requestUrl!! }
        assertThat(requests.map { it.encodedPath }).contains("/api/2/events/segmentation", "/api/2/retention", "/api/2/sessions/average").doesNotContain("/api/2/users", "/api/2/funnels")
        val campaignQueries = requests.mapNotNull { it.queryParameter("e") }.filter { it.contains("initial_utm_campaign") }
        assertThat(campaignQueries).isNotEmpty().allMatch { it.contains("actual-campaign") && !it.contains("campaign-key") }
    }

    @Test fun `자격 증명이 없으면 다음 키까지 시도하지 않고 HTTP 없이 실행을 마친다`() {
        val missing = AnalyticsTestFixture(AmplitudeDashboardProperties(), clock)
        val catalog = AnalyticsBatchCatalog { listOf(metric("one"), metric("two")).map { AnalyticsBatchMetric(it, day.minusDays(2)) } }
        val result = AnalyticsBatchRunner(catalog, missing.snapshots, AnalyticsBatchPolicy(), clock).run()!!
        assertThat(result.reason).isEqualTo("no_credentials"); assertThat(result.result).isEqualTo("failed")
        assertThat(result.keys).isEqualTo(1); assertThat(result.cost).isZero(); assertThat(server.requestCount).isZero()
    }

}
