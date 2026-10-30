package com.knk.manyak.admin.analytics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import tools.jackson.databind.JsonNode
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 5-5-12의 백그라운드 갱신 행. 요청 대기 10초는 테스트 시간을 줄이려 2초로, 대역 지연도 같은 비율로 줄였다. */
class AnalyticsBackgroundRefreshTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var service: CampaignAnalyticsService
    private lateinit var metrics: List<DailyAnalyticsMetric>
    private val day = LocalDate.parse("2026-10-23")
    private val now = Instant.parse("2026-10-23T03:00:00Z")
    private val expired = now.minusSeconds(11 * 60)
    @Volatile private var delayMillis = 0L
    @Volatile private var gate: CountDownLatch? = null
    private val events = ListAppender<ILoggingEvent>().apply { start() }
    private val logger = org.slf4j.LoggerFactory.getLogger(AnalyticsSnapshots::class.java) as Logger
    private val query get() = CampaignQuery().apply { from = day.minusDays(6).toString(); to = day.toString() }

    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(now),
            AnalyticsPolicy(requestWaitSeconds = 2))
        val settings = CampaignSettings(listOf(CampaignDefinition("4", "4차", "campaign", day.minusDays(30))),
            mapOf("instagram" to listOf("ig"), "threads" to listOf("th", "threads"), "dcinside" to listOf("dc")))
        service = CampaignAnalyticsService(settings, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        metrics = service.trendMetrics("campaign")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                gate?.await(30, TimeUnit.SECONDS)
                val url = r.requestUrl!!
                val from = LocalDate.parse(url.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val to = LocalDate.parse(url.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList()
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(List(dates.size) { 1 })))))
                    .setBodyDelay(delayMillis, TimeUnit.MILLISECONDS)
            }
        }
        // D-6~D-1은 오늘 받은 유효한 값이다(D-1은 오늘 받은 어제 값, 나머지는 날짜가 끝난 뒤 받은 확정일 값).
        metrics.forEach { metric -> fixture.store.saveDaily(metric.definition, (1L..6L).associate { day.minusDays(it) to number(3, now.minusSeconds(60)) }) }
        logger.addAppender(events)
    }
    @AfterEach fun stop() { logger.detachAppender(events); gate?.countDown(); fixture.executor.await(); server.shutdown() }
    private fun number(n: Long, fetched: Instant) = AnalyticsValue(fixture.mapper.valueToTree<JsonNode>(n), fetched)
    private fun storeExpiredToday() = metrics.forEach { fixture.store.saveDaily(it.definition, mapOf(day to number(3, expired))) }
    private fun refreshedLogs() = events.list.filter { it.formattedMessage.startsWith("admin_analytics_refreshed") }

    @Test fun `캠페인 추이는 스펙의 기기 유형 세 조회를 포함한 16개 정의다`() {
        assertThat(metrics).hasSize(16)
        assertThat(metrics.count { it.definition.parameters.any { p -> p.first == "e" && p.second.contains("device_type") } }).isEqualTo(3)
    }
    @Test fun `만료 값 즉시 응답 - 대역을 기다리지 않고 만료된 오늘 값으로 응답한 뒤 백그라운드가 오늘만 갱신한다`() {
        storeExpiredToday()
        gate = CountDownLatch(1)
        val response = service.analytics(query)
        assertThat(response.pendingDates).isEmpty()
        assertThat(response.asOf).isEqualTo(expired)
        assertThat(response.daily.single { it.date == day }.activeUsers).isEqualTo(3)
        gate!!.countDown(); fixture.executor.await()
        assertThat(server.requestCount).isEqualTo(metrics.size)
        repeat(metrics.size) { val url = server.takeRequest().requestUrl!!; assertThat(url.queryParameter("start")).isEqualTo("20261023"); assertThat(url.queryParameter("end")).isEqualTo("20261023") }
        val after = service.analytics(query)
        assertThat(after.asOf).isAfter(expired)
        assertThat(after.daily.single { it.date == day }.activeUsers).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(metrics.size)
    }
    @Test fun `값 없음 대기 - 저장 값이 없는 날짜는 한도 안에 끝난 갱신을 기다려 그 값으로 응답한다`() {
        delayMillis = 30
        val response = service.analytics(query)
        assertThat(server.requestCount).isEqualTo(metrics.size)
        assertThat(response.pendingDates).isEmpty()
        assertThat(response.daily.map { it.date }).contains(day)
        assertThat(response.daily.single { it.date == day }.activeUsers).isEqualTo(1)
    }
    @Test fun `값 없음 대기 초과 - 대기 한도에 저장된 값만 다시 읽어 응답하고 갱신은 끝까지 계속한다`() {
        delayMillis = 200
        val started = System.nanoTime()
        val response = service.analytics(query)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertThat(elapsed).isBetween(Duration.ofSeconds(2), Duration.ofMillis(3000))
        assertThat(response.daily.map { it.date }).containsExactlyElementsOf((6L downTo 1L).map { day.minusDays(it) })
        assertThat(response.pendingDates).containsExactly(day)
        assertThat(refreshedLogs()).isEmpty()
        fixture.executor.await()
        assertThat(server.requestCount).isEqualTo(metrics.size)
        assertThat(refreshedLogs()).singleElement().extracting { it.formattedMessage }.asString().contains("queries=${metrics.size}")
        assertThat(service.analytics(query).pendingDates).isEmpty()
        assertThat(server.requestCount).isEqualTo(metrics.size)
    }
    @Test fun `갱신 중 동시 요청 - 다른 요청은 갱신을 시작하지도 기다리지도 않는다`() {
        delayMillis = 200
        val first = java.util.concurrent.CompletableFuture.supplyAsync { service.analytics(query) }
        val until = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (server.requestCount == 0) { check(System.nanoTime() < until); Thread.sleep(5) }
        repeat(2) {
            val started = System.nanoTime()
            val response = service.analytics(query)
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500))
            assertThat(response.pendingDates).containsExactly(day)
        }
        assertThat(first.get(10, TimeUnit.SECONDS).pendingDates).containsExactly(day)
        fixture.executor.await()
        assertThat(server.requestCount).isEqualTo(metrics.size)
    }
    @Test fun `실행기 거부 - 표식을 풀고 저장 값으로 응답하며 쿨다운을 걸지 않는다`() {
        storeExpiredToday()
        fixture.executor.rejecting = true
        val response = service.analytics(query)
        assertThat(response.pendingDates).isEmpty(); assertThat(response.asOf).isEqualTo(expired)
        assertThat(server.requestCount).isZero()
        fixture.executor.rejecting = false
        service.analytics(query)
        fixture.executor.await()
        assertThat(server.requestCount).isEqualTo(metrics.size)
    }
    @Test fun `요청 대기 기본값은 10초다`() {
        assertThat(AnalyticsPolicy().requestWaitSeconds).isEqualTo(10)
    }
}
