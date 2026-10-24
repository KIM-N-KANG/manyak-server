package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.*
import java.time.temporal.ChronoUnit

@ExtendWith(OutputCaptureExtension::class)
class LangfuseCostSplittingTests {
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
    private val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties(), clock)
    private val properties = AdminLangfuseProperties("https://example.invalid", "public", "secret", "prod", callInterval = Duration.ofMillis(1))
    private val calls = mutableListOf<LangfuseInterval>()
    private val range = AnalyticsRange(LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-21"))
    private fun snapshots(response: LangfuseMetricsClient.(LangfuseInterval) -> LangfuseMetricsPage): LangfuseCostSnapshots {
        val client = object : LangfuseMetricsClient(properties, fixture.mapper) {
            override fun fetch(interval: LangfuseInterval, now: Instant): LangfuseMetricsPage {
                calls.add(interval)
                return response(interval)
            }
        }
        return LangfuseCostSnapshots(fixture.store, client, properties, fixture.mapper, clock)
    }
    private fun LangfuseMetricsClient.capped(interval: LangfuseInterval): LangfuseMetricsPage =
        decode(fixture.mapper.readTree(List(1000) { "{}" }.joinToString(",", "{\"data\":[", "]}")), interval)
    private fun LangfuseMetricsClient.hourly(interval: LangfuseInterval): LangfuseMetricsPage {
        val rows = (0L until interval.hours).map { hour -> mapOf(
            "time_dimension" to interval.from.plus(hour, ChronoUnit.HOURS).toString(),
            "traceName" to "채팅 턴", "providedModelName" to "gpt-x", "sum_totalCost" to "0.001", "sum_totalTokens" to 10,
        ) }
        return decode(fixture.mapper.valueToTree(mapOf("data" to rows)), interval)
    }
    private fun service(snapshots: LangfuseCostSnapshots): AdminAiCostService {
        val repository = object : AdminMetricReadRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())) {
            override fun aiCost(range: AnalyticsRange): List<Map<String, Any?>> = emptyList()
        }
        return AdminAiCostService(repository, snapshots, AdminAiCostProperties(traceFeatures = mapOf("채팅 턴" to "chat_response")), clock)
    }

    @Test fun `천 행인 72시간은 버리고 36시간 두 구간을 합쳐 하루 전체만 배열로 저장한다`() {
        lateinit var s: LangfuseCostSnapshots
        s = snapshots { interval ->
            if (calls.size == 1) capped(interval) else {
                if (calls.size == 2) assertThat(fixture.store.daily(s.definition, range.from, range.to)).isEmpty()
                if (calls.size == 3) assertThat(fixture.store.daily(s.definition, range.from, range.to).keys).containsExactly(range.to)
                hourly(interval)
            }
        }
        val result = s.load(range)
        assertThat(calls.map { it.hours }).containsExactly(72L, 36L, 36L)
        assertThat(calls[1].from).isEqualTo(calls[2].until)
        assertThat(result).hasSize(3)
        result.values.forEach {
            assertThat(it.entries.single().costUsd).isEqualByComparingTo("0.024")
            assertThat(it.entries.single().totalTokens).isEqualTo(240)
        }
        fixture.store.daily(s.definition, range.from, range.to).values.forEach {
            assertThat(it.value.isArray).isTrue()
            assertThat(it.value[0].properties().map { field -> field.key }).containsExactlyInAnyOrder("traceName", "model", "costUsd", "totalTokens")
            assertThat(it.value[0]["costUsd"].asText().toBigDecimal()).isEqualByComparingTo("0.024")
            assertThat(it.finalizedAt).isNotNull()
        }
    }

    @Test fun `분할 호출도 여섯 번 예산에 포함하고 완성하지 못한 날짜는 pendingDates다`() {
        val s = snapshots { interval -> if (interval.hours > 18) capped(interval) else hourly(interval) }
        val result = service(s).aiCost(AdminChartsRequest().apply { from = range.from.toString(); to = range.to.toString() })
        assertThat(calls.map { it.hours }).containsExactly(72L, 36L, 18L, 18L, 36L, 18L)
        assertThat(result.pendingDates).containsExactly(range.from)
        assertThat(result.daily[0].byFeature).isEmpty()
        assertThat(result.totals.costKrw).isEqualByComparingTo("67.2")
        assertThat(fixture.store.daily(s.definition, range.from, range.to).keys).containsExactlyInAnyOrder(range.from.plusDays(1), range.to)
        val resumed = s.load(range)
        assertThat(calls).hasSize(7)
        assertThat(calls.last().hours).isEqualTo(18)
        assertThat(resumed).hasSize(3)
        assertThat(resumed[range.from]!!.entries.single().totalTokens).isEqualTo(240)
    }

    @Test fun `한 날짜가 여섯 번 안에 완성되지 않아도 다음 요청에서 분할을 이어받는다`() {
        val day = AnalyticsRange(range.to, range.to)
        val s = snapshots { interval -> if (interval.hours > 3) capped(interval) else hourly(interval) }
        assertThat(s.load(day)).isEmpty()
        assertThat(calls).hasSize(6)
        var values = s.load(day)
        if (values.isEmpty()) values = s.load(day)
        assertThat(values).hasSize(1)
        assertThat(values[day.from]!!.entries.single().totalTokens).isEqualTo(240)
        assertThat(calls).hasSize(15)
    }

    @Test fun `최소 한 시간 구간도 천 행이면 parse_error와 쿨다운이며 부분 날짜는 저장하지 않는다`(output: CapturedOutput) {
        val s = snapshots { interval -> if (interval.hours == 2L) hourly(interval) else capped(interval) }
        val day = AnalyticsRange(range.to, range.to)
        assertThat(s.load(day)).isEmpty()
        assertThat(calls.map { it.hours }).containsExactly(24L, 12L, 6L, 3L, 2L, 1L)
        assertThat(output.out).contains("admin_ai_cost_fetch_failed reason=parse_error status=200")
        assertThat(s.load(day)).isEmpty()
        assertThat(calls).hasSize(6)
        assertThat(fixture.store.daily(s.definition, day.from, day.to)).isEmpty()
    }

    @Test fun `분할 도중 실패하면 완성한 날짜만 남기고 일부만 받은 날짜는 저장하지 않는다`() {
        val s = snapshots { interval ->
            when (calls.size) {
                1 -> capped(interval)
                2 -> hourly(interval)
                else -> throw LangfuseFetchFailure(AiCostFailureReason.http_error, 500)
            }
        }
        val result = s.load(range)
        assertThat(result.keys).containsExactly(range.to)
        assertThat(result[range.to]!!.entries.single().costUsd).isEqualByComparingTo("0.024")
        assertThat(fixture.store.daily(s.definition, range.from, range.to).keys).containsExactly(range.to)
    }
}
