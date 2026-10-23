package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.LocalDate

@ConfigurationProperties("manyak.admin.ai-cost")
class AdminAiCostProperties(
    val usdKrwRate: BigDecimal = BigDecimal("1400"),
    val chatImageUnitCostKrw: BigDecimal = BigDecimal("48"),
    val traceFeatures: Map<String, String> = emptyMap(),
) {
    init {
        require(usdKrwRate.signum() > 0 && chatImageUnitCostKrw.signum() >= 0)
        require(traceFeatures.values.all { it in setOf("chat_response", "choice_generation", "storyline_generation", "story_completion") })
    }
}

@Service
@EnableConfigurationProperties(AdminAiCostProperties::class)
class AdminAiCostService(
    private val repository: AdminMetricReadRepository,
    private val snapshots: LangfuseCostSnapshots,
    private val properties: AdminAiCostProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private fun Map<String, Any?>.n(key: String) = (this[key] as Number?)?.toLong() ?: 0L
    fun aiCost(query: AdminChartsRequest): AiCostResponse {
        val range = query.resolve(clock).let { AnalyticsRange(it.from, it.to) }
        val costs = snapshots.load(range)
        if (costs.isEmpty()) throw AdminAiCostUnavailable()
        val rows = repository.aiCost(range).groupBy { LocalDate.ofEpochDay(it.n("metric_day")) }
        val daily = range.dates().map { date ->
            val db = rows[date].orEmpty().associateBy { it["feature"].toString() to it["model"] as String? }
            val amounts = costs[date]?.entries.orEmpty().filter { it.traceName in properties.traceFeatures }
                .groupBy { properties.traceFeatures.getValue(it.traceName) to it.model }
                .mapValues { (_, entries) -> entries.sumOf { it.costUsd } * properties.usdKrwRate }
            val keys = (db.keys + amounts.keys).sortedWith(compareBy<Pair<String, String?>> { it.first }.thenBy { it.second })
            AiCostDay(date, keys.map { key ->
                val row = db[key].orEmpty()
                val image = key.first == "chat_image"
                val calls = row.n("calls")
                AiCostItem(key.first, key.second, calls, if (image) 0 else row.n("missing"),
                    if (image) 0 else row.n("input"), if (image) 0 else row.n("output"),
                    if (image) properties.chatImageUnitCostKrw * calls.toBigDecimal() else amounts[key])
            })
        }
        val raw = costs.values.flatMap { it.entries }
        val unmapped = raw.filter { it.traceName !in properties.traceFeatures }.groupBy { it.traceName }.toSortedMap()
            .map { (trace, entries) -> AiCostUnmapped(trace, entries.sumOf { it.costUsd } * properties.usdKrwRate) }
        val all = daily.flatMap { it.byFeature }
        fun sum(items: List<AiCostItem>) = items.sumOf { it.costKrw ?: BigDecimal.ZERO }
        fun per(features: Set<String>, denominator: String): BigDecimal? {
            val calls = all.filter { it.feature == denominator }.sumOf { it.calls }
            return if (calls == 0L) null else sum(all.filter { it.feature in features }).divide(calls.toBigDecimal(), MathContext.DECIMAL128)
        }
        // 사용량은 있으나 범위 비용 합이 0인 모델이다. 단가 누락과 정상 0원은 구별하지 않는다.
        val unpriced = raw.filter { it.model != null }.groupBy { it.model!! }
            .filterValues { entries -> entries.any { it.totalTokens > 0 } && entries.sumOf { it.costUsd }.signum() == 0 }.keys.sorted()
        return AiCostResponse(costs.values.minOf { it.fetchedAt }, range.from, range.to, "KRW", properties.chatImageUnitCostKrw, daily,
            AiCostTotals(sum(all) + unmapped.sumOf { it.costKrw }, per(setOf("chat_response", "choice_generation"), "chat_response"),
                per(setOf("storyline_generation", "story_completion"), "story_completion")), unpriced,
            range.dates().filter { it !in costs }, properties.usdKrwRate, unmapped)
    }
}
