package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class AmplitudeBudgetTests {
    private val now = Instant.parse("2026-10-22T03:00:00Z")
    @Test fun `서로 다른 키도 500 예산 확인과 예약을 함께 처리하고 5분 뒤 복구한다`() {
        val clock = MutableAnalyticsClock(now); val budget = AmplitudeBudget(clock); val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<Boolean> { budget.reserve(251) } }.map { it.get(1, TimeUnit.SECONDS) }
            assertThat(results.count { it }).isEqualTo(1)
            assertThat(budget.reserve(249)).isTrue(); assertThat(budget.reserve(1)).isFalse()
            clock.at = now.plusSeconds(299); assertThat(budget.reserve(1)).isFalse()
            clock.at = now.plusSeconds(300); assertThat(budget.reserve(500)).isTrue()
        } finally { pool.shutdownNow() }
    }
    @ParameterizedTest @ValueSource(booleans = [true, false])
    fun `코호트 포함 긴 조회는 최근 41일과 별도 오늘 값의 비용만 예약한다`(includesToday: Boolean) {
        MockWebServer().use { server ->
            server.start(); val mapper = ObjectMapper(); val clock = MutableAnalyticsClock(now)
            val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", "team", server.url("/").toString()), clock)
            val today = LocalDate.parse("2026-10-22"); val to = if (includesToday) today else today.minusDays(1); val from = to.minusDays(119)
            server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
                val start = LocalDate.parse(r.requestUrl!!.queryParameter("start"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val end = LocalDate.parse(r.requestUrl!!.queryParameter("end"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                val dates = start.datesUntil(end.plusDays(1)).toList()
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(List(dates.size) { 1 })))))
            } }
            val result = fixture.service.dashboard(com.knk.manyak.admin.dto.AdminChartsRequest().apply { this.from = from.toString(); this.to = to.toString() })
            assertThat(result.daily).hasSize(41)
            assertThat(fixture.budget.reserve(if (includesToday) 8 else 2)).isTrue()
            assertThat(fixture.budget.reserve(1)).isFalse()
        }
    }
    @Test fun `부분 성공의 비용만 남기고 실패 및 미전송 예약분은 되돌린다`() {
        MockWebServer().use { server ->
            server.start(); val clock = MutableAnalyticsClock(now)
            val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", "team", server.url("/").toString()), clock)
            assertThat(fixture.budget.reserve(490)).isTrue()
            server.enqueue(MockResponse().setBody("{\"data\":{\"xValues\":[\"2026-10-22\"],\"series\":[[1]]}}")); server.enqueue(MockResponse().setResponseCode(500))
            assertThatThrownBy { fixture.service.dashboard(com.knk.manyak.admin.dto.AdminChartsRequest().apply { from = "2026-10-19"; to = "2026-10-19" }) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(fixture.budget.reserve(8)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        }
    }
    @Test fun `예산 부족은 호출 없이 그 키에 60초 쿨다운을 건다`() {
        MockWebServer().use { server ->
            server.start(); val clock = MutableAnalyticsClock(now)
            val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock, AnalyticsPolicy(budgetWindowSeconds = 1))
            assertThat(fixture.budget.reserve(499)).isTrue()
            assertThatThrownBy { fixture.service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
            clock.at = now.plusSeconds(59); assertThatThrownBy { fixture.service.dashboard(7) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
            assertThat(server.requestCount).isZero()
            val today = LocalDate.parse("2026-10-22"); val mapper = ObjectMapper()
            repeat(6) { server.enqueue(MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to List(7) { today.minusDays(6).plusDays(it.toLong()).toString() }, "series" to listOf(List(7) { 1 })))))) }
            clock.at = now.plusSeconds(60); assertThat(fixture.service.dashboard(7).todayActiveUsers).isEqualTo(DashboardPlatformCounts(1, 1, 1))
            assertThat(server.requestCount).isEqualTo(6)
        }
    }
    @Test fun `독립 클라이언트도 공용 계수기로 외부 호출을 직렬화한다`() {
        MockWebServer().use { server ->
            val active = AtomicInteger(); val maximum = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    try { Thread.sleep(50) } finally { active.decrementAndGet() }
                    return MockResponse().setBody("{\"data\":{\"xValues\":[\"2026-10-22\"],\"series\":[[1]]}}")
                }
            }; server.start()
            val budget = AmplitudeBudget(); val properties = AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString())
            val clients = List(2) { AmplitudeRestClient(properties, ObjectMapper(), budget) }; val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
            val def = AnalyticsDefinition("test", "/api/2/users", listOf("m" to "active"), 1, ObjectMapper())
            try {
                val tasks = (0..3).map { index -> pool.submit { start.await(); val date = LocalDate.parse("2026-10-22"); clients[index % 2].fetch(def, AnalyticsRange(date, date), System.nanoTime() + TimeUnit.SECONDS.toNanos(3)) } }
                start.countDown(); tasks.forEach { it.get(3, TimeUnit.SECONDS) }
                assertThat(server.requestCount).isEqualTo(4); assertThat(maximum.get()).isEqualTo(1)
            } finally { pool.shutdownNow() }
        }
    }
}
