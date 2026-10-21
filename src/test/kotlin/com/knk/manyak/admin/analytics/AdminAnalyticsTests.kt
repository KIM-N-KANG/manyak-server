package com.knk.manyak.admin.analytics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.Base64
import java.util.concurrent.*

class AdminAnalyticsTests {
    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()
    private lateinit var clock: MutableAnalyticsClock
    private val today = LocalDate.parse("2026-10-22")
    private val now = Instant.parse("2026-10-21T15:30:00Z") // KST 날짜는 다음 날
    private val log = LoggerFactory.getLogger(AdminAnalyticsService::class.java) as Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        clock = MutableAnalyticsClock(now)
        appender = ListAppender<ILoggingEvent>().apply { start() }; log.addAppender(appender)
    }
    @AfterEach fun cleanup() { server.shutdown(); log.detachAppender(appender); appender.stop() }
    private fun service(cohort: String = "", key: String = "test-api", secret: String = "test-secret") = AdminAnalyticsService(
        AmplitudeDashboardClient(AmplitudeDashboardProperties(key, secret, cohort, server.url("/").toString()), mapper), clock)
    private fun body(start: LocalDate = today.minusDays(89), base: Long = 0): String = mapper.writeValueAsString(mapOf("data" to mapOf(
        "xValues" to (0L..89).map { start.plusDays(it).toString() }, "series" to listOf((0L..89).map { base + it }),
        "seriesMeta" to listOf("All Users"))))
    private fun enqueuePair(start: LocalDate = today.minusDays(89), base: Long = 0) {
        server.enqueue(MockResponse().setBody(body(start, base)))
        server.enqueue(MockResponse().setBody(body(start, base + 100)))
    }
    private fun failure(reason: String, status: Int? = null) {
        assertThat(appender.list).hasSize(1)
        assertThat(appender.list.single().formattedMessage).contains("admin_analytics_fetch_failed", "reason=$reason")
        if (status != null) assertThat(appender.list.single().formattedMessage).contains("status=$status")
        assertThat(appender.list.single().throwableProxy).isNull()
        assertThat(appender.list.single().formattedMessage).doesNotContain("test-api", "test-secret", "sensitive response")
    }

    @Test fun `90일 사용자 수 두 요청과 기본 인증 코호트 및 KST 날짜를 검증하고 days는 공유 캐시를 자른다`() {
        enqueuePair()
        val service = service("team-cohort")
        val response = service.dashboard(7)
        assertThat(response.from).isEqualTo(today.minusDays(6)); assertThat(response.to).isEqualTo(today)
        assertThat(response.daily).hasSize(7); assertThat(response.todayActiveUsers).isEqualTo(89)
        assertThat(response.daily.last().newUsers).isEqualTo(189)
        assertThat(response.asOf).isEqualTo(now)
        assertThat(service.dashboard(90).daily).hasSize(90)
        assertThat(service.dashboard(30).daily).hasSize(30)
        assertThat(server.requestCount).isEqualTo(2)
        for (metric in listOf("active", "new")) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            val url = request.requestUrl!!
            assertThat(request.method).isEqualTo("GET")
            assertThat(url.encodedPath).isEqualTo("/api/2/users")
            assertThat(url.queryParameter("e")).isNull()
            assertThat(url.queryParameter("m")).isEqualTo(metric); assertThat(url.queryParameter("i")).isEqualTo("1")
            assertThat(url.queryParameter("start")).isEqualTo("20260725"); assertThat(url.queryParameter("end")).isEqualTo("20261022")
            assertThat(request.getHeader("Authorization")).isEqualTo("Basic " + Base64.getEncoder().encodeToString("test-api:test-secret".toByteArray()))
            assertThat(mapper.readTree(url.queryParameter("s")!!)).isEqualTo(mapper.readTree("""[{"prop":"userdata_cohort","op":"is not","values":["team-cohort"]}]"""))
        }
    }
    @Test fun `코호트가 비면 조건이 없고 10분 경계에서 재조회한다`() {
        val service = service(); enqueuePair(); service.dashboard(7)
        repeat(2) { assertThat(server.takeRequest().requestUrl!!.queryParameter("s")).isNull() }
        clock.at = now.plusSeconds(599); service.dashboard(90); assertThat(server.requestCount).isEqualTo(2)
        clock.at = now.plusSeconds(600); enqueuePair(base = 1000)
        assertThat(service.dashboard(7).todayActiveUsers).isEqualTo(1089)
        assertThat(server.requestCount).isEqualTo(4)
    }
    @Test fun `만료된 캐시 갱신 실패는 두 지표와 asOf를 이전 값으로 보존하고 부분 성공으로 덮지 않는다`() {
        val service = service(); enqueuePair(); val cached = service.dashboard(7)
        clock.at = now.plusSeconds(600)
        server.enqueue(MockResponse().setBody(body(base = 2000))); server.enqueue(MockResponse().setResponseCode(500).setBody("sensitive response"))
        assertThat(service.dashboard(7)).isEqualTo(cached); failure("http_error", 500)
        assertThat(service.dashboard(7)).isEqualTo(cached)
        assertThat(server.requestCount).isEqualTo(4)
        clock.at = now.plusSeconds(661)
        enqueuePair(base = 3000)
        assertThat(service.dashboard(7).todayActiveUsers).isEqualTo(3089)
    }
    @ParameterizedTest @ValueSource(ints = [302, 400, 401, 403, 429, 500, 503])
    fun `외부 HTTP 오류는 고정 실패로 변환하고 응답 본문과 키를 기록하지 않는다`(status: Int) {
        server.enqueue(MockResponse().setResponseCode(status).setBody("sensitive response test-api test-secret"))
        val service = service()
        assertThatThrownBy { service.dashboard(30) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        clock.at = now.plusSeconds(30)
        assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        failure(if (status == 429) "rate_limited" else "http_error", status)
    }
    @ParameterizedTest @ValueSource(strings = ["{}", "not-json", "{\"data\":{\"xValues\":[],\"series\":[]}}"])
    fun `형식 오류와 데이터 누락은 0으로 간주하지 않는다`(body: String) {
        server.enqueue(MockResponse().setBody(body))
        assertThatThrownBy { service().dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        failure("http_error", 200)
    }
    @Test fun `지표는 날짜별 한 그룹의 음이 아닌 정수여야 하고 날짜 순서를 맞춰 응답한다`() {
        val valid = mapper.readTree(body()) as tools.jackson.databind.node.ObjectNode
        val invalids = listOf<tools.jackson.databind.JsonNode>(
            valid.deepCopy().also { (it.path("data").path("series")[0] as tools.jackson.databind.node.ArrayNode).set(0, mapper.valueToTree(-1)) },
            valid.deepCopy().also { (it.path("data").path("series")[0] as tools.jackson.databind.node.ArrayNode).set(0, mapper.valueToTree(1.5)) },
            valid.deepCopy().also { (it.path("data").path("xValues") as tools.jackson.databind.node.ArrayNode).set(0, mapper.valueToTree(today.toString())) },
            valid.deepCopy().also { (it.path("data").path("series") as tools.jackson.databind.node.ArrayNode).add(mapper.createArrayNode()) },
        )
        for (invalid in invalids) {
            server.enqueue(MockResponse().setBody(invalid.toString()))
            assertThatThrownBy { service().dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        }
        val reversed = mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to (0L..89).map { today.minusDays(it).toString() }, "series" to listOf((0L..89).map { 89 - it }))))
        repeat(2) { server.enqueue(MockResponse().setBody(reversed)) }
        val response = service().dashboard(7)
        assertThat(response.daily.map { it.date }).isSorted()
        assertThat(response.todayActiveUsers).isEqualTo(89)
    }
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `갱신 중 다른 요청은 외부 조회를 기다리지 않고 캐시 또는 사용 불가를 받는다`(hasCache: Boolean) {
        val service = service()
        val previous = if (hasCache) { enqueuePair(); service.dashboard(7) } else null
        clock.at = now.plusSeconds(600)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return MockResponse().setBody(body(base = 1000))
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val refreshing = pool.submit<AdminAnalyticsResponse> { service.dashboard(7) }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val others = (1..3).map { pool.submit<AdminAnalyticsResponse?> {
                if (previous == null) {
                    assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
                    null
                } else service.dashboard(7)
            } }
            others.forEach { assertThat(it.get(1, TimeUnit.SECONDS)).isEqualTo(previous) }
            assertThat(requests.get()).isEqualTo(1)
            release.countDown()
            val updated = refreshing.get(3, TimeUnit.SECONDS)
            assertThat(updated.todayActiveUsers).isEqualTo(1089)
            assertThat(service.dashboard(90).asOf).isEqualTo(updated.asOf)
            assertThat(requests.get()).isEqualTo(2)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `실패 완료부터 60초 동안 동시 요청도 재호출하지 않고 쿨다운 경계부터 복구한다`(hasCache: Boolean) {
        val service = service()
        val previous = if (hasCache) { enqueuePair(); service.dashboard(7) } else null
        clock.at = now.plusSeconds(600)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setResponseCode(500)
            }
        }
        fun fallback(): AdminAnalyticsResponse? = if (previous == null) {
            assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
            null
        } else service.dashboard(7)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val first = pool.submit<AdminAnalyticsResponse?> { fallback() }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
            clock.at = now.plusSeconds(603) // 실패 시각은 호출 시작보다 3초 뒤다.
            release.countDown()
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(previous)
            val calls = server.requestCount
            for (seconds in listOf(603L, 633L, 662L)) {
                clock.at = now.plusSeconds(seconds)
                val others = (1..3).map { pool.submit<AdminAnalyticsResponse?> { fallback() } }
                others.forEach { assertThat(it.get(1, TimeUnit.SECONDS)).isEqualTo(previous) }
                assertThat(server.requestCount).isEqualTo(calls)
            }
            assertThat(appender.list).hasSize(1) // 쿨다운 요청마다 실패 로그를 쌓지 않는다.
            server.dispatcher = okhttp3.mockwebserver.QueueDispatcher()
            enqueuePair(base = 2000)
            clock.at = now.plusSeconds(663)
            assertThat(service.dashboard(7).todayActiveUsers).isEqualTo(2089)
            assertThat(server.requestCount).isEqualTo(calls + 2)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `키 둘 중 하나라도 비면 HTTP 호출 없이 고정 503 대상이다`() {
        for ((key, secret) in listOf("" to "test-secret", "test-api" to "", " " to " ")) {
            appender.list.clear()
            assertThatThrownBy { service(key = key, secret = secret).dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
            failure("no_credentials")
        }
        assertThat(server.requestCount).isZero()
    }
    @Test fun `응답 헤더 뒤 본문이 멈춰도 전체 호출은 5초로 제한된다`() {
        server.enqueue(MockResponse().setBody(body()).setBodyDelay(6, TimeUnit.SECONDS))
        val service = service()
        val started = System.nanoTime()
        assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(4800), Duration.ofSeconds(7))
        assertThatThrownBy { service.dashboard(30) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        failure("timeout")
    }
    @Test fun `활성 조회 뒤 신규 조회도 남은 시간 안에서 끝나야 한다`() {
        server.enqueue(MockResponse().setBody(body()).setBodyDelay(3, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val service = service()
        val started = System.nanoTime()
        assertThatThrownBy { service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7))
        assertThatThrownBy { service.dashboard(30) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        failure("timeout")
    }
    @Test fun `KST 날짜가 바뀌면 10분 이내라도 오늘까지 다시 조회한다`() {
        clock.at = Instant.parse("2026-10-22T14:59:00Z")
        val service = service(); enqueuePair(); service.dashboard(7)
        clock.at = Instant.parse("2026-10-22T15:00:00Z")
        enqueuePair(today.minusDays(88))
        assertThat(service.dashboard(7).to).isEqualTo(today.plusDays(1))
    }
    @Test fun `days는 허용된 세 값만 받고 잘못된 값에는 HTTP 호출이 없다`() {
        for (days in listOf(0, 10, 100)) assertThatThrownBy { service().dashboard(days) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(server.requestCount).isZero()
    }
}

class MutableAnalyticsClock(@Volatile var at: Instant) : Clock() {
    override fun instant() = at
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
