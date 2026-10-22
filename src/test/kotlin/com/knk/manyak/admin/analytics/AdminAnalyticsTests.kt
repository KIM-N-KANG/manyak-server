package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.concurrent.*

class AdminAnalyticsTests {
    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()
    private lateinit var clock: MutableAnalyticsClock
    private val today = LocalDate.parse("2026-10-22")
    private val now = Instant.parse("2026-10-21T15:30:00Z")
    private val log = LoggerFactory.getLogger(AdminAnalyticsService::class.java) as Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }; clock = MutableAnalyticsClock(now)
        appender = ListAppender<ILoggingEvent>().apply { start() }; log.addAppender(appender)
    }
    @AfterEach fun cleanup() { server.shutdown(); log.detachAppender(appender); appender.stop() }
    private fun service(key: String = "test-api", secret: String = "test-secret", cohort: String = "") = AdminAnalyticsService(
        AmplitudeDashboardClient(AmplitudeDashboardProperties(key, secret, cohort, server.url("/").toString()), mapper), clock)
    private fun body(from: LocalDate = today.minusDays(6), count: Int = 7, base: Long = 0): String = mapper.writeValueAsString(mapOf("data" to mapOf(
        "xValues" to (0 until count).map { from.plusDays(it.toLong()).toString() }, "series" to listOf((0 until count).map { base + it }), "seriesMeta" to listOf("All Users"))))
    private fun pair(from: LocalDate = today.minusDays(6), count: Int = 7, base: Long = 0) {
        server.enqueue(MockResponse().setBody(body(from, count, base))); server.enqueue(MockResponse().setBody(body(from, count, base + 100)))
    }
    private fun range(from: String, to: String) = AdminChartsRequest().apply { this.from = from; this.to = to }

    @Test fun `days와 같은 직접 범위는 캐시를 공유하고 요청 범위만 조회하며 KST와 코호트를 적용한다`() {
        val service = service(cohort = "team-cohort"); pair()
        val response = service.dashboard(7)
        assertThat(response.from).isEqualTo(today.minusDays(6)); assertThat(response.to).isEqualTo(today)
        assertThat(response.daily).hasSize(7); assertThat(response.todayActiveUsers).isEqualTo(6)
        assertThat(response.daily.last().newUsers).isEqualTo(106)
        assertThat(service.dashboard(range("2026-10-16", "2026-10-22"))).isEqualTo(response)
        assertThat(server.requestCount).isEqualTo(2)
        for (metric in listOf("active", "new")) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!; val url = request.requestUrl!!
            assertThat(url.encodedPath).isEqualTo("/api/2/users"); assertThat(url.queryParameter("m")).isEqualTo(metric)
            assertThat(url.queryParameter("start")).isEqualTo("20261016"); assertThat(url.queryParameter("end")).isEqualTo("20261022")
            assertThat(url.queryParameter("i")).isEqualTo("1"); assertThat(url.queryParameter("e")).isNull()
            assertThat(request.getHeader("Authorization")).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("test-api:test-secret".toByteArray()))
            assertThat(url.queryParameter("s")).contains("team-cohort", "userdata_cohort")
        }
    }
    @Test fun `과거 범위는 오늘 활성 수를 별도 조회하고 범위별 캐시와 만료 및 쿨다운을 지킨다`() {
        val service = service(); val request = range("2026-01-01", "2026-01-02")
        pair(LocalDate.parse(request.from), 2); server.enqueue(MockResponse().setBody(body(today, 1, 42)))
        val cached = service.dashboard(request)
        assertThat(cached.todayActiveUsers).isEqualTo(42); assertThat(cached.daily.last().activeUsers).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(3)
        repeat(2) { server.takeRequest() }
        assertThat(server.takeRequest().requestUrl!!.queryParameter("start")).isEqualTo("20261022")
        clock.at = now.plusSeconds(599); assertThat(service.dashboard(request)).isEqualTo(cached)
        pair(); assertThat(service.dashboard(7).daily).hasSize(7)
        clock.at = now.plusSeconds(600); server.enqueue(MockResponse().setResponseCode(500))
        assertThat(service.dashboard(request)).isEqualTo(cached)
        clock.at = now.plusSeconds(659); assertThat(service.dashboard(request)).isEqualTo(cached)
        assertThat(server.requestCount).isEqualTo(6)
        clock.at = now.plusSeconds(660); pair(LocalDate.parse(request.from), 2); server.enqueue(MockResponse().setBody(body(today, 1, 43)))
        assertThat(service.dashboard(request).todayActiveUsers).isEqualTo(43)
    }
    @Test fun `366일 범위도 마지막 날까지 조회하고 자정 뒤 오늘 활성 수를 갱신한다`() {
        val service = service(); val request = range("2025-10-22", "2026-10-22")
        pair(LocalDate.parse(request.from), 366)
        assertThat(service.dashboard(request).daily).hasSize(366)
        clock.at = Instant.parse("2026-10-22T15:00:00Z")
        pair(LocalDate.parse(request.from), 366); server.enqueue(MockResponse().setBody(body(today.plusDays(1), 1, 99)))
        assertThat(service.dashboard(request).todayActiveUsers).isEqualTo(99)
    }
    @ParameterizedTest @ValueSource(ints = [302, 400, 401, 403, 429, 500, 503])
    fun `외부 실패는 고정 예외와 60초 쿨다운이며 인증값과 본문을 로그에 남기지 않는다`(status: Int) {
        val service = service(); server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive response test-api test-secret"))
        assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        clock.at = now.plusSeconds(59); assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(appender.list).hasSize(1)
        assertThat(appender.list.single().formattedMessage).contains("status=$status").doesNotContain("test-api", "test-secret", "sensitive response")
        assertThat(appender.list.single().throwableProxy).isNull()
        clock.at = now.plusSeconds(60); pair(); assertThat(service.dashboard(7).todayActiveUsers).isEqualTo(6)
    }
    @ParameterizedTest @ValueSource(strings = ["{}", "not-json", "{\"data\":{\"xValues\":[],\"series\":[]}}"])
    fun `형식 오류와 누락은 0으로 간주하지 않는다`(body: String) {
        server.enqueue(MockResponse().setBody(body)); assertThatThrownBy { service().dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
    }
    @Test fun `수치는 음이 아닌 정수이고 날짜 중복은 거부한다`() {
        for (invalid in listOf(body().replace("[0,1,2,3,4,5,6]", "[-1,1,2,3,4,5,6]"), body().replace("2026-10-16", "2026-10-17"), body().replace("[0,1,2,3,4,5,6]", "[0.5,1,2,3,4,5,6]"))) {
            server.enqueue(MockResponse().setBody(invalid)); assertThatThrownBy { service().dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        }
    }
    @Test fun `갱신은 부분 성공으로 캐시를 덮어쓰지 않는다`() {
        val service = service(); pair(); val previous = service.dashboard(7); clock.at = now.plusSeconds(600)
        server.enqueue(MockResponse().setBody(body(base = 2000))); server.enqueue(MockResponse().setResponseCode(500))
        assertThat(service.dashboard(7)).isEqualTo(previous)
    }
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `동시 갱신은 한 요청만 호출하고 나머지는 즉시 이전 값 또는 사용 불가를 받는다`(hasCache: Boolean) {
        val service = service(); val previous = if (hasCache) { pair(); service.dashboard(7) } else null
        clock.at = now.plusSeconds(600)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (calls.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return MockResponse().setBody(body(base = 1000))
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val pending = pool.submit<AdminAnalyticsResponse> { service.dashboard(7) }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val others = (1..3).map { pool.submit<AdminAnalyticsResponse?> {
                if (previous == null) { assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java); null } else service.dashboard(7)
            } }
            others.forEach { assertThat(it.get(1, TimeUnit.SECONDS)).isEqualTo(previous) }
            assertThat(calls.get()).isEqualTo(1); release.countDown()
            assertThat(pending.get(3, TimeUnit.SECONDS).todayActiveUsers).isEqualTo(1006)
            assertThat(calls.get()).isEqualTo(2)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `키가 없으면 HTTP 없이 사용 불가이고 잘못된 기간도 호출하지 않는다`() {
        for ((key, secret) in listOf("" to "test-secret", "test-api" to "")) assertThatThrownBy { service(key, secret).dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        for (days in listOf(0, 10, 100)) assertThatThrownBy { service().dashboard(days) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(server.requestCount).isZero()
    }
    @Test fun `본문이 멈춰도 5초 전체 예산을 지킨다`() {
        server.enqueue(MockResponse().setBody(body()).setBodyDelay(6, TimeUnit.SECONDS))
        val started = System.nanoTime(); assertThatThrownBy { service().dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(4800), Duration.ofSeconds(7))
    }
}

class MutableAnalyticsClock(@Volatile var at: Instant) : Clock() {
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
