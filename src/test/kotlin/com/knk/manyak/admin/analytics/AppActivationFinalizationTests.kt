package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*

class AppActivationFinalizationTests {
    @Test fun `앱 활성 퍼널은 D-2 당일 다음 날 값으로 확정하지 않고 오늘 다시 받는다`() {
        MockWebServer().use { server ->
            server.start()
            val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
            val f = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock)
            val metric = MetricDefinitions(AnalyticsQueryFactory(f.mapper), f.mapper).appActivation()
            val date = LocalDate.parse("2026-10-21"); val range = AnalyticsRange(date, date)
            val yesterday = clock.instant().minusSeconds(86400)
            f.store.saveDaily(metric.definition, mapOf(date to AnalyticsValue(f.mapper.valueToTree(listOf(20,4)), yesterday, yesterday)))
            server.enqueue(MockResponse().setBody("""{"data":[{"cumulativeRaw":[20,6]}]}"""))
            assertThat(f.snapshots.missingDates(metric, range)).containsExactly(date)
            assertThat(f.store.daily(metric.definition, date, date)[date]!!.finalizedAt).isNull()
            f.snapshots.load("app", range, listOf(metric)); f.executor.await()
            assertThat(server.requestCount).isEqualTo(1)
            val saved = f.store.daily(metric.definition, date, date).getValue(date)
            assertThat(saved.value[1].asLong()).isEqualTo(6)
            assertThat(saved.finalizedAt).isNotNull()
            clock.at = clock.at.plusSeconds(86400)
            assertThat(f.snapshots.missingDates(metric, range)).isEmpty()
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
    @Test fun `어제 앱 활성은 오늘 받은 값이면 확정하지 않고 내일 다시 받는다`() {
        MockWebServer().use { server ->
            server.start()
            val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
            val f = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock)
            val metric = MetricDefinitions(AnalyticsQueryFactory(f.mapper), f.mapper).appActivation()
            val date = LocalDate.parse("2026-10-22"); val range = AnalyticsRange(date, date)
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"cumulativeRaw":[20,6]}]}""")) }
            f.snapshots.load("app", range, listOf(metric)); f.executor.await()
            assertThat(f.store.daily(metric.definition, date, date)[date]!!.finalizedAt).isNull()
            assertThat(f.snapshots.missingDates(metric, range)).isEmpty()
            clock.at = clock.at.plusSeconds(86400)
            assertThat(f.snapshots.missingDates(metric, range)).containsExactly(date)
            f.snapshots.load("app", range, listOf(metric)); f.executor.await()
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(f.store.daily(metric.definition, date, date)[date]!!.finalizedAt).isNotNull()
        }
    }
    @Test fun `새벽 배치는 앱 활성에만 어제와 그저께를 조회한다`() {
        MockWebServer().use { server ->
            server.start()
            val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
            val f = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock)
            val metric = MetricDefinitions(AnalyticsQueryFactory(f.mapper), f.mapper).appActivation()
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"cumulativeRaw":[20,6]}]}""")) }
            val runner = AnalyticsBatchRunner(AnalyticsBatchCatalog { listOf(AnalyticsBatchMetric(metric, LocalDate.parse("2026-07-01"))) }, f.snapshots, AnalyticsBatchPolicy(), clock)
            assertThat(runner.run()!!.cost).isEqualTo(8)
            assertThat((1..2).map { server.takeRequest().requestUrl!!.queryParameter("start") }).containsExactly("20261022", "20261021")
            assertThat(f.snapshots.missingDates(metric, AnalyticsRange(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-22")))).isEmpty()
        }
    }
}
