package com.knk.manyak.admin.analytics

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/** 날짜를 제외한 조회 정의를 만들고 원값 해석기를 함께 둔다. */
@Component
class AnalyticsQueryFactory(private val mapper: ObjectMapper) {
    fun daily(metric: String, event: Map<String, Any>, aggregation: String = "uniques", integral: Boolean = true): DailyAnalyticsMetric {
        val def = AnalyticsDefinition(metric, "/api/2/events/segmentation", listOf("e" to mapper.writeValueAsString(event), "m" to aggregation, "i" to "1"), 1, mapper)
        return DailyAnalyticsMetric(def) { data, range ->
            if (integral) range.dates().zip(AmplitudeResults.series(data, range).map { mapper.valueToTree<JsonNode>(it) }).toMap()
            else AmplitudeResults.parse {
                val dates = data["xValues"]; val groups = data["series"]
                require(dates != null && dates.isArray && dates.size() == range.days && groups != null && groups.isArray && groups.size() == 1)
                val numbers = groups[0]; require(numbers.isArray && numbers.size() == range.days)
                val values = linkedMapOf<java.time.LocalDate, JsonNode>()
                dates.forEachIndexed { i, date ->
                    val number = numbers[i]; require(date.isString && number.isNumber && number.asDouble().isFinite() && number.asDouble() >= 0)
                    require(values.put(AmplitudeResults.date(date), mapper.valueToTree(number.asDouble())) == null)
                }
                range.dates().associateWith { requireNotNull(values[it]) }
            }
        }
    }
    fun dailyGrouped(metric: String, event: Map<String, Any>): DailyAnalyticsMetric {
        val def = AnalyticsDefinition(metric, "/api/2/events/segmentation", listOf("e" to mapper.writeValueAsString(event),
            "m" to "uniques", "i" to "1", "limit" to "1000"), 25, mapper)
        return DailyAnalyticsMetric(def) { data, range -> AmplitudeResults.dailyGroups(data, range).mapValues { mapper.valueToTree<JsonNode>(it.value) } }
    }
    /** [users]는 퍼널 사용자 집합 `n`이다. `new`는 범위 안에 처음 기록된 사용자만 센다. */
    fun funnel(metric: String, range: AnalyticsRange, events: List<Map<String, Any>>, users: String = "active", conversionDays: Long = 1): RangeAnalyticsMetric {
        val def = AnalyticsDefinition(metric, "/api/2/funnels", events.map { "e" to mapper.writeValueAsString(it) } +
            listOf("mode" to "ordered", "cs" to (conversionDays * 86400).toString(), "n" to users, "i" to "1"), events.size * 2, mapper)
        return RangeAnalyticsMetric(def, range, conversionDays) { mapper.valueToTree(AmplitudeResults.funnel(it, events.size)) }
    }
    fun grouped(metric: String, range: AnalyticsRange, event: Map<String, Any>, extra: List<Pair<String, String>> = emptyList()): RangeAnalyticsMetric {
        val def = AnalyticsDefinition(metric, "/api/2/events/segmentation", listOf("e" to mapper.writeValueAsString(event), "m" to "uniques", "i" to "1", "limit" to "1000") + extra, 25, mapper)
        return RangeAnalyticsMetric(def, range) { mapper.valueToTree(AmplitudeResults.groups(it, 1)) }
    }
}
