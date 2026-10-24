package com.knk.manyak.admin.analytics

import okhttp3.Credentials
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.time.*
import java.util.concurrent.*

class LangfuseCostSnapshotsTests {
    private lateinit var server: MockWebServer
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
    private val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties(), clock)
    private val mapper = fixture.mapper
    private val range = AnalyticsRange(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-23"))
    @BeforeEach fun start() { server = MockWebServer().apply { start() } }
    @AfterEach fun stop() { server.shutdown() }
    private fun properties(secret: String = "secret", interval: Duration = Duration.ofMillis(1), timeout: Duration = Duration.ofSeconds(10)) =
        AdminLangfuseProperties(server.url("/").toString(), "public", secret, "prod", requestTimeout = timeout, callInterval = interval)
    private fun snapshots(p: AdminLangfuseProperties = properties()) = LangfuseCostSnapshots(fixture.store, LangfuseMetricsClient(p, mapper), p, mapper, clock)
    private fun respond(vararg rows: Map<String, Any?>) { server.enqueue(MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to rows.toList())))) }
    private fun row(time: String, cost: Any = "0.004", tokens: Any = "1000", model: String? = "gpt-x") =
        mapOf("time_dimension" to time, "traceName" to "채팅 턴", "providedModelName" to model, "sum_totalCost" to cost, "sum_totalTokens" to tokens)

    @Test fun `공식 observations 질의와 기본 인증으로 받고 UTC 시간을 KST 날짜로 합산한다`() {
        respond(row("2026-10-21T13:00:00.000Z"), row("2026-10-21T14:00:00.000Z", 0.001), row("2026-10-21T15:00:00.000Z", "0.002", model = null))
        val result = snapshots().load(range)
        val request = server.takeRequest()
        assertThat(request.path).startsWith("/api/public/v2/metrics?query=")
        assertThat(request.getHeader("Authorization")).isEqualTo(Credentials.basic("public", "secret"))
        val query = mapper.readTree(request.requestUrl!!.queryParameter("query"))
        assertThat(query["view"].asText()).isEqualTo("observations")
        assertThat(query["dimensions"].toList().map { it["field"].asText() }).containsExactly("traceName", "providedModelName")
        assertThat(query["metrics"].toList().map { it["measure"].asText() }).containsExactly("totalCost", "totalTokens")
        assertThat(query["metrics"].all { it["aggregation"].asText() == "sum" }).isTrue()
        assertThat(query["timeDimension"]["granularity"].asText()).isEqualTo("hour")
        assertThat(query["fromTimestamp"].asText()).isEqualTo("2026-10-20T15:00:00Z")
        assertThat(query["toTimestamp"].asText()).isEqualTo("2026-10-23T14:59:59.999Z")
        assertThat(query["filters"].any { it["column"].asText() == "environment" && it["value"].asText() == "prod" }).isTrue()
        assertThat(query["filters"].any { it["column"].asText() == "name" && it["operator"].asText() == "none of" && it["value"][0].asText() == "이미지 생성:자식" }).isTrue()
        assertThat(query["config"]["row_limit"].asInt()).isEqualTo(1000)
        assertThat(query["filters"].toList().map { it["column"].asText() }).containsExactly("environment", "name")
        assertThat(request.path).contains("%7B", "%22", "%EC")
        assertThat(result[range.from]!!.entries.single().costUsd).isEqualByComparingTo("0.005")
        assertThat(result[range.from.plusDays(1)]!!.entries.single().model).isNull()
        assertThat(result[range.to]!!.entries).isEmpty()
    }

    @Test fun `확정일은 고정 어제는 한 시간 오늘은 십 분이고 저장 값은 재시작 뒤에도 재사용한다`() {
        respond()
        val s = snapshots()
        s.load(range)
        assertThat(s.load(range)).hasSize(3)
        assertThat(snapshots().load(range)).hasSize(3)
        assertThat(server.requestCount).isEqualTo(1)
        val saved = fixture.store.daily(s.definition, range.from, range.to)
        assertThat(saved[range.from]!!.finalizedAt).isNotNull()
        assertThat(saved[range.from.plusDays(1)]!!.finalizedAt).isNull()
        clock.at = clock.at.plusSeconds(600)
        respond(); s.load(range)
        val first = mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        val today = mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        assertThat(today["fromTimestamp"].asText()).isEqualTo("2026-10-22T15:00:00Z")
        clock.at = clock.at.plusSeconds(3000)
        respond(); s.load(range)
        val yesterday = mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        assertThat(yesterday["fromTimestamp"].asText()).isEqualTo("2026-10-21T15:00:00Z")
        assertThat(first["fromTimestamp"].asText()).isEqualTo("2026-10-20T15:00:00Z")
    }

    @Test fun `서로 다른 날짜 요청은 자정 관측을 한 번만 세고 전날 시작한 추적도 관측 날짜로 센다`() {
        val traceStartedAt = Instant.parse("2026-10-20T14:59:00Z")
        val observations = listOf(
            Instant.parse("2026-10-20T15:00:00Z") to "0.001",
            Instant.parse("2026-10-20T15:01:00Z") to "0.004",
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val query = mapper.readTree(request.requestUrl!!.queryParameter("query"))
                val from = Instant.parse(query["fromTimestamp"].asText())
                val to = Instant.parse(query["toTimestamp"].asText())
                // v2는 추적 시작 시각이 아닌 각 관측 시각으로 범위를 고른다.
                val rows = observations.filter { (startedAt, _) -> startedAt >= from && startedAt <= to }
                    .map { (startedAt, cost) -> row(startedAt.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString(), cost) }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to rows)))
            }
        }
        val s = snapshots()
        val previous = range.from.minusDays(1)
        assertThat(s.load(AnalyticsRange(previous, previous))[previous]!!.entries).isEmpty()
        val result = s.load(AnalyticsRange(range.from, range.from))
        assertThat(traceStartedAt).isBefore(range.from.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant())
        assertThat(result[range.from]!!.entries.single().costUsd).isEqualByComparingTo("0.005")
        val earlier = server.takeRequest()
        val later = server.takeRequest()
        val earlierQuery = mapper.readTree(earlier.requestUrl!!.queryParameter("query"))
        val laterQuery = mapper.readTree(later.requestUrl!!.queryParameter("query"))
        assertThat(earlierQuery["toTimestamp"].asText()).isEqualTo("2026-10-20T14:59:59.999Z")
        assertThat(laterQuery["fromTimestamp"].asText()).isEqualTo("2026-10-20T15:00:00Z")
        assertThat(laterQuery["view"].asText()).isEqualTo("observations")
        assertThat(later.path).startsWith("/api/public/v2/metrics?query=")
    }

    @Test fun `자정 전 받은 값은 다음 날 다시 받고 확정일 승격 시 끝난 뒤 받은 값만 고정한다`() {
        clock.at = Instant.parse("2026-10-21T14:59:00Z")
        val day = AnalyticsRange(range.from, range.from)
        val s = snapshots()
        respond(); s.load(day)
        clock.at = Instant.parse("2026-10-21T15:01:00Z")
        respond(); s.load(day)
        assertThat(server.requestCount).isEqualTo(2)
        clock.at = Instant.parse("2026-10-22T15:01:00Z")
        snapshots().load(day)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(fixture.store.daily(s.definition, day.from, day.to)[day.from]!!.finalizedAt).isNotNull()
    }

    @Test fun `90일도 최근 18일을 받고 다음 요청은 보관 기간 안 나머지 11일만 채운다`() {
        val long = AnalyticsRange(range.to.minusDays(89), range.to)
        val s = snapshots()
        repeat(6) { respond() }
        val first = s.load(long)
        assertThat(first).hasSize(18)
        assertThat(first.keys.min()).isEqualTo(range.to.minusDays(17))
        for (i in 0..5) {
            val query = mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
            assertThat(Instant.parse(query["fromTimestamp"].asText()).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()).isEqualTo(range.to.minusDays(2L + 3 * i))
            assertThat(Duration.between(Instant.parse(query["fromTimestamp"].asText()), Instant.parse(query["toTimestamp"].asText()).plusMillis(1))).isEqualTo(Duration.ofDays(3))
        }
        repeat(6) { respond() }
        assertThat(s.load(long)).hasSize(29)
        assertThat(server.requestCount).isEqualTo(10)
    }

    @Test fun `유효한 중간 날짜를 다시 받지 않고 빈 연속 구간에 두 호출을 쓴다`() {
        val s = snapshots()
        respond(); s.load(AnalyticsRange(range.from, range.from))
        respond(); respond()
        val wider = AnalyticsRange(range.from.minusDays(2), range.to)
        assertThat(s.load(wider)).hasSize(5)
        server.takeRequest()
        val newer = mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        val older = mapper.readTree(server.takeRequest().requestUrl!!.queryParameter("query"))
        assertThat(newer["fromTimestamp"].asText()).isEqualTo("2026-10-21T15:00:00Z")
        assertThat(older["toTimestamp"].asText()).isEqualTo("2026-10-20T14:59:59.999Z")
    }

    @Test fun `429 Retry-After와 실패 쿨다운은 범위가 달라도 적용하고 저장된 날짜는 반환한다`() {
        val s = snapshots()
        respond(); s.load(AnalyticsRange(range.from, range.from))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("secret must never be logged"))
        assertThat(s.load(range).keys).containsExactly(range.from)
        assertThat(s.load(AnalyticsRange(range.to, range.to))).isEmpty()
        clock.at = clock.at.plusSeconds(119)
        s.load(range)
        assertThat(server.requestCount).isEqualTo(2)
        clock.at = clock.at.plusSeconds(1)
        respond(); assertThat(s.load(range)).hasSize(3)
    }

    @Test fun `429 HTTP 날짜 Retry-After도 읽고 헤더가 없거나 잘못되면 60초 쉰다`() {
        for (header in listOf<String?>(null, "invalid", "Fri, 23 Oct 2026 03:02:00 GMT")) {
            clock.at = Instant.parse("2026-10-23T03:00:00Z")
            val s = snapshots()
            val response = MockResponse().setResponseCode(429)
            if (header != null) response.setHeader("Retry-After", header)
            server.enqueue(response)
            s.load(range)
            val before = server.requestCount
            clock.at = clock.at.plusSeconds(if (header?.endsWith("GMT") == true) 119 else 59)
            s.load(range)
            assertThat(server.requestCount).isEqualTo(before)
        }
    }

    @Test fun `500 형식 오류 타임아웃은 저장 없이 실패하며 쿨다운 중 재호출하지 않는다`() {
        val responses = listOf(MockResponse().setResponseCode(500), MockResponse().setBody("{\"data\":[{\"sum_totalCost\":\"oops\"}]}"),
            MockResponse().setBody("{\"data\":[]}").setBodyDelay(300, TimeUnit.MILLISECONDS))
        for (response in responses) {
            val s = snapshots(properties(timeout = Duration.ofMillis(100)))
            server.enqueue(response)
            assertThat(s.load(range)).isEmpty()
            val before = server.requestCount
            assertThat(s.load(range)).isEmpty()
            assertThat(server.requestCount).isEqualTo(before)
        }
    }

    @Test fun `설정 하나라도 없으면 저장 값이 있어도 실패하고 외부 호출하지 않는다`() {
        respond(); snapshots().load(range)
        val p = properties(secret = "")
        assertThatThrownBy { snapshots(p).load(range) }.isInstanceOf(AdminAiCostUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `두 호출 사이 일 초를 보장하고 동시 요청은 저장 값만 읽는다`() {
        val started = CopyOnWriteArrayList<Long>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.add(System.nanoTime()); entered.countDown(); release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("{\"data\":[]}")
            }
        }
        val s = snapshots(properties(interval = Duration.ofSeconds(1)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Map<LocalDate, LangfuseCostDay>> { s.load(AnalyticsRange(range.to.minusDays(5), range.to)) }
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue()
            assertThat(s.load(range)).isEmpty()
            release.countDown()
            assertThat(pending.get(5, TimeUnit.SECONDS)).hasSize(6)
            assertThat(started[1] - started[0]).isGreaterThanOrEqualTo(Duration.ofSeconds(1).toNanos())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
