package com.knk.manyak.admin.analytics

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 배치와 화면이 동일한 매개변수 해시를 만들도록 조회 정의를 한 곳에서 생성한다. */
@Component
class MetricDefinitions(private val queries: AnalyticsQueryFactory, private val mapper: ObjectMapper) {
    val platforms = listOf("all", "web", "android", "unknown")
    fun filters(platform: String): List<Map<String, Any>> = AdminMetricEvents.common(false) + if (platform == "all") emptyList() else
        listOf(AdminMetricEvents.filter("platform", listOf(when (platform) { "web" -> "Web"; "android" -> "Android"; "unknown" -> "(none)"; else -> error("Invalid platform") }), "user"))
    fun activity(): List<DailyAnalyticsMetric> = platforms.flatMap { platform -> listOf("active", "new").map { event ->
        queries.daily("activity_$event", AdminMetricEvents.event("_$event", filters(platform)))
    } } + listOf("web", "android").map { queries.daily("activity_messages", AdminMetricEvents.event(AdminMetricEvents.MESSAGE, filters(it)), "average", false) }
    fun mau(to: LocalDate): RangeAnalyticsMetric {
        val range = AnalyticsRange(to.minusDays(29), to)
        val def = AnalyticsDefinition("activity_mau", "/api/2/events/segmentation", listOf("e" to mapper.writeValueAsString(AdminMetricEvents.event("_active", filters("all"))), "m" to "uniques", "i" to "1"), 1, mapper)
        return RangeAnalyticsMetric(def, range) { data -> AmplitudeResults.parse {
            val values = data["seriesCollapsed"]; require(values != null && values.isArray && values.size() == 1)
            val value = values[0].let { if (it.isArray) { require(it.size() == 1); it[0] } else it }.let { if (it.isObject) it["value"] else it }
            mapper.valueToTree(AmplitudeResults.number(requireNotNull(value)))
        } }
    }

    fun retention(): List<DailyAnalyticsMetric> = listOf("all", "web", "android").map { platform ->
        val def = AnalyticsDefinition("users_retention", "/api/2/retention", listOf("se" to mapper.writeValueAsString(AdminMetricEvents.event("_new", filters(platform))),
            "re" to mapper.writeValueAsString(AdminMetricEvents.event("_active", filters(platform))), "i" to "1"), 8, mapper)
        DailyAnalyticsMetric(def, retention = true) { data, range -> AmplitudeResults.parse {
            val series = data["series"]; require(series != null && series.isArray && series.size() == 1)
            val values = series[0]["values"]; require(values != null && values.isObject)
            val result = linkedMapOf<LocalDate, JsonNode>()
            for (date in range.dates()) {
                val array = values[date.toString()]
                    ?: values[date.format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH))]
                    ?: values[date.format(DateTimeFormatter.ofPattern("MMM dd, uuuu", Locale.ENGLISH))] ?: values[date.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))]
                    ?: values[date.format(DateTimeFormatter.ofPattern("MMM dd", Locale.ENGLISH))]
                require(array != null && array.isArray && !array.isEmpty)
                array.forEach { point ->
                    val count = AmplitudeResults.number(requireNotNull(point["count"])); val outof = AmplitudeResults.number(requireNotNull(point["outof"]))
                    require(count <= outof && point["incomplete"]?.isBoolean == true)
                }
                result[date] = array
            }
            result
        } }
    }
    fun sessions(): List<DailyAnalyticsMetric> = listOf("all", "web", "android").map { platform ->
        val conditions = listOf(AdminMetricEvents.condition("country", listOf("South Korea", "(none)"))) + if (platform == "all") emptyList() else
            listOf(AdminMetricEvents.condition("platform", listOf(if (platform == "web") "Web" else "Android")))
        val def = AnalyticsDefinition("users_session_average", "/api/2/sessions/average", listOf("i" to "1", "s" to mapper.writeValueAsString(conditions)), if (platform == "all") 8 else 12, mapper)
        DailyAnalyticsMetric(def) { data, range -> decimalSeries(data, range) }
    }
    private fun decimalSeries(data: JsonNode, range: AnalyticsRange): Map<LocalDate, JsonNode> = AmplitudeResults.parse {
        val x = data["xValues"]; val series = data["series"]
        require(x != null && x.isArray && x.size() == range.days && series != null && series.isArray && series.size() == 1 && series[0].isArray && series[0].size() == range.days)
        val values = linkedMapOf<LocalDate, JsonNode>()
        x.forEachIndexed { i, date ->
            val value = series[0][i]; require(value.isNumber && value.asDouble().isFinite() && value.asDouble() >= 0)
            require(values.put(AmplitudeResults.date(date), value) == null)
        }
        range.dates().associateWith { requireNotNull(values[it]) }
    }
    val qualityEvents = linkedMapOf("chatLoadError" to "client_chat_loadError_shown", "storyListLoadError" to "client_storyList_loadError_shown",
        "storyCompleteError" to "client_storyCreate_completeError_shown", "creditShortage" to "client_creditShortageDialog_shown", "creditChargeViewed" to "client_creditCharge_viewed",
        "attendanceClicked" to "client_creditCharge_attendanceButton_clicked", "inviteSucceeded" to "client_invite_codeInput_succeeded")
    fun quality(platform: String): List<DailyAnalyticsMetric> {
        val filters = filters(platform)
        val all = queries.daily("quality_messages", AdminMetricEvents.event(AdminMetricEvents.MESSAGE, filters), "totals")
        val modes = listOf("choice", "plain", "block").map { queries.daily("quality_messages", AdminMetricEvents.event(AdminMetricEvents.MESSAGE, filters + AdminMetricEvents.filter("input_mode", listOf(it))), "totals") }
        val errors = queries.daily("quality_stream_errors", AdminMetricEvents.event("client_chat_streamError_shown", filters), "totals")
        return listOf(all) + modes + errors + qualityEvents.map { (key, event) -> queries.daily("quality_$key", AdminMetricEvents.event(event, filters)) }
    }
}
