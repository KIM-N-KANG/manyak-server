package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.*

class FunnelFinalizationTests {
    @ParameterizedTest @ValueSource(longs = [1, 30])
    fun `전환 창이 끝난 뒤 받은 값만 확정하고 그 전에는 하루 한 번 갱신한다`(days: Long) {
        MockWebServer().use { server ->
            server.start()
            val clock = MutableAnalyticsClock(Instant.parse("2026-10-30T03:00:00Z"))
            val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock)
            val date = LocalDate.parse("2026-10-30").minusDays(days + 1)
            val range = AnalyticsRange(date, date)
            val metric = AnalyticsQueryFactory(fixture.mapper).funnel("test_funnel", range, listOf(AdminMetricEvents.event("_active")), conversionDays = days)
            val previous = clock.instant().minusSeconds(86400)
            // 옛 확정 판정으로 저장된 행도 전환 창 안에서 받은 값이면 확정을 풀어 다시 받는다.
            fixture.store.saveRange(metric.definition, date, date, AnalyticsValue(fixture.mapper.valueToTree(listOf(1)), previous, previous))
            server.enqueue(MockResponse().setBody("""{"data":[{"cumulativeRaw":[2]}]}"""))
            fixture.snapshots.load("funnel", range, emptyList(), rangeMetrics = listOf(metric))
            fixture.executor.await()
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(fixture.store.range(metric.definition, date, date)!!.finalizedAt).isNotNull()
            fixture.snapshots.load("funnel", range, emptyList(), rangeMetrics = listOf(metric))
            assertThat(server.requestCount).isEqualTo(1)
        }
    }
    @Test fun `30일 창이 남은 지난 범위는 10분이 지나도 같은 날 다시 받지 않고 다음 날 받는다`() {
        MockWebServer().use { server ->
            server.start()
            val clock = MutableAnalyticsClock(Instant.parse("2026-10-30T03:00:00Z"))
            val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), clock)
            val date = LocalDate.parse("2026-10-20")
            val range = AnalyticsRange(date, date)
            val metric = AnalyticsQueryFactory(fixture.mapper).funnel("long_funnel", range, listOf(AdminMetricEvents.event("_active")), conversionDays = 30)
            fun load() = fixture.snapshots.load("long_funnel", range, emptyList(), rangeMetrics = listOf(metric))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"cumulativeRaw":[2]}]}""")) }
            load(); fixture.executor.await()
            clock.at = clock.at.plusSeconds(601)
            load(); fixture.executor.await()
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(fixture.store.range(metric.definition, date, date)!!.finalizedAt).isNull()
            clock.at = clock.at.plusSeconds(86400)
            load(); fixture.executor.await()
            assertThat(server.requestCount).isEqualTo(2)
            assertThat(fixture.store.range(metric.definition, date, date)!!.finalizedAt).isNull()
        }
    }
}
