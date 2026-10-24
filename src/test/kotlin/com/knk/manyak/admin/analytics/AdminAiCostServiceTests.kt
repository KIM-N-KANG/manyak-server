package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.math.BigDecimal
import java.time.*

class AdminAiCostServiceTests {
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
    private val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties(), clock)
    private val date = LocalDate.parse("2026-10-21")
    private val query = AdminChartsRequest().apply { from = date.toString(); to = date.toString() }
    private var rows = listOf<Map<String, Any?>>()
    private var entries = listOf<LangfuseCostEntry>()
    private val repository = object : AdminMetricReadRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())) {
        override fun aiCost(range: AnalyticsRange) = rows
    }
    private val properties = AdminLangfuseProperties("https://example.invalid", "public", "secret", "prod", callInterval = Duration.ofMillis(1))
    private val client = object : LangfuseMetricsClient(properties, fixture.mapper) {
        override fun fetch(interval: LangfuseInterval, now: Instant) = LangfuseMetricsPage(interval.dates().associateWith { entries })
    }
    private val snapshots = LangfuseCostSnapshots(fixture.store, client, properties, fixture.mapper, clock)
    private val mapping = mapOf("채팅 턴" to "chat_response", "채팅 선택지" to "choice_generation", "스토리라인 생성" to "storyline_generation", "스토리 컴파일" to "story_completion")
    private fun service(rate: String = "1400", mapping: Map<String, String> = this.mapping) = AdminAiCostService(repository, snapshots,
        AdminAiCostProperties(usdKrwRate = BigDecimal(rate), traceFeatures = mapping), clock)
    private fun db(feature: String, model: String?, calls: Long = 1, missing: Long = 0) = mapOf("metric_day" to date.toEpochDay(), "feature" to feature,
        "model" to model, "calls" to calls, "missing" to missing, "input" to 300L, "output" to 100L)
    private fun cost(trace: String, model: String?, usd: String, tokens: Long = 1000) = LangfuseCostEntry(trace, model, BigDecimal(usd), tokens)

    @Test fun `정본 예시 0점004와 0점001 달러를 호출 2회로 나누면 턴당 3점5원이다`() {
        rows = listOf(db("chat_response", "gpt-x", 2), db("choice_generation", "gpt-x"))
        entries = listOf(cost("채팅 턴", "gpt-x", "0.004"), cost("채팅 선택지", "gpt-x", "0.001"))
        val result = service().aiCost(query)
        assertThat(result.daily.single().byFeature.single { it.feature == "chat_response" }.costKrw).isEqualByComparingTo("5.6")
        assertThat(result.totals.costPerTurnKrw).isEqualByComparingTo("3.5")
        assertThat(result.totals.costPerStoryKrw).isNull()
    }

    @Test fun `한쪽 조합 누락과 null 모델 미매핑 실시간 이미지와 사용량은 있으나 비용 0인 모델을 함께 보존한다`() {
        rows = listOf(db("chat_response", "db-only", 2, 1), db("chat_response", null), db("chat_image", null, 3), db("story_completion", "db-only"))
        entries = listOf(cost("채팅 턴", "lf-only", "0.004"), cost("채팅 턴", null, "0.001"), cost("새 기능", "new", "0.002"),
            cost("스토리 컴파일", "cover-image", "0.005"), cost("채팅 선택지", "unpriced", "0"))
        val result = service().aiCost(query)
        val items = result.daily.single().byFeature
        assertThat(items.single { it.feature == "chat_response" && it.model == "db-only" }.costKrw).isNull()
        assertThat(items.single { it.model == "lf-only" }.calls).isZero()
        assertThat(items.single { it.feature == "chat_response" && it.model == null }.calls).isEqualTo(1)
        val image = items.single { it.feature == "chat_image" }
        assertThat(image.costKrw).isEqualByComparingTo("144")
        assertThat(listOf(image.callsWithoutTokens, image.inputTokens, image.outputTokens)).containsOnly(0)
        assertThat(result.unpricedModels).containsExactly("unpriced")
        assertThat(result.unmapped.single().costKrw).isEqualByComparingTo("2.8")
        assertThat(result.totals.costKrw).isEqualByComparingTo("160.8")
        assertThat(result.totals.costPerStoryKrw).isEqualByComparingTo("7")
    }

    @Test fun `사용량이 0이거나 다른 추적에 비용이 있는 모델은 비용 0 모델로 표시하지 않는다`() {
        entries = listOf(cost("채팅 턴", "priced", "0"), cost("채팅 선택지", "priced", "0.001"), cost("새 기능", "empty", "0", 0), cost("새 기능", "free", "0"))
        assertThat(service().aiCost(query).unpricedModels).containsExactly("free")
    }

    @Test fun `확정일 원본은 유지하고 환율과 매핑 변경만 새 설정으로 적용한다`() {
        entries = listOf(cost("새 기능", "gpt-x", "0.004"))
        val first = service().aiCost(query)
        val second = service("1500", mapping + ("새 기능" to "chat_response")).aiCost(query)
        assertThat(first.unmapped.single().costKrw).isEqualByComparingTo("5.6")
        assertThat(second.unmapped).isEmpty()
        assertThat(second.daily.single().byFeature.single().costKrw).isEqualByComparingTo("6")
        assertThat(second.asOf).isEqualTo(first.asOf)
    }
}
