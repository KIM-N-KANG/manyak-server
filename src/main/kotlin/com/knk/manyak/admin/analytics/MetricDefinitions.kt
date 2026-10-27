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
    fun filters(platform: String): List<Map<String, Any>> = AdminMetricEvents.platformFilters(platform)
    fun activity(): List<DailyAnalyticsMetric> = platforms.flatMap { platform -> listOf("active", "new").map { event ->
        queries.daily("activity_$event", AdminMetricEvents.event("_$event", filters(platform)))
    } } + listOf(queries.daily("activity_active", AdminMetricEvents.event("_active", filters("android_overseas")))) + listOf("web", "android").map { queries.daily("activity_messages", AdminMetricEvents.event(AdminMetricEvents.MESSAGE, filters(it)), "average", false) }
    fun mau(to: LocalDate, platform: String = "all"): RangeAnalyticsMetric {
        val range = AnalyticsRange(to.minusDays(29), to)
        val def = AnalyticsDefinition("activity_mau", "/api/2/events/segmentation", listOf("e" to mapper.writeValueAsString(AdminMetricEvents.event("_active", filters(platform))), "m" to "uniques", "i" to "1"), 1, mapper)
        return RangeAnalyticsMetric(def, range) { data -> AmplitudeResults.parse {
            val values = data["seriesCollapsed"]; require(values != null && values.isArray && values.size() == 1)
            val value = values[0].let { if (it.isArray) { require(it.size() == 1); it[0] } else it }.let { if (it.isObject) it["value"] else it }
            mapper.valueToTree(AmplitudeResults.number(requireNotNull(value)))
        } }
    }

    fun retention(): List<DailyAnalyticsMetric> = listOf("all", "web", "android").map { platform ->
        // 리텐션 전체는 평균·코호트처럼 국내 사용자 전체 한 조회를 유지한다.
        val filters = if (platform == "all") AdminMetricEvents.common(false) else filters(platform)
        val def = AnalyticsDefinition("users_retention", "/api/2/retention", listOf("se" to mapper.writeValueAsString(AdminMetricEvents.event("_new", filters)),
            "re" to mapper.writeValueAsString(AdminMetricEvents.event("_active", filters)), "i" to "1"), 8, mapper)
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
    /** 가입, 턴 도달, 채팅 옵션의 플랫폼 값은 국내 조건 안의 세 조회(web, android, unknown 순)이고 전체는 그 합이다. */
    val splitPlatforms = listOf("web", "android", "unknown")
    /** 플랫폼마다 구글, 카카오 순서다. */
    fun signupPlatforms(): List<DailyAnalyticsMetric> = splitPlatforms.flatMap { platform -> listOf(AdminMetricEvents.GOOGLE, AdminMetricEvents.KAKAO).map { event ->
        queries.daily("users_signup_platform", AdminMetricEvents.event(event, filters(platform) + AdminMetricEvents.filter("is_new_user", listOf("true"))))
    } }
    private fun savedTurns(metric: String, platform: String, segment: String, filter: Map<String, Any>) = queries.daily(metric, AdminMetricEvents.event(AdminMetricEvents.CHAT_SUCCEEDED,
        filters(platform) + AdminMetricEvents.filter("is_regenerated", listOf("false")) + filter + AdminMetricEvents.segment(segment)), "totals")
    /** 플랫폼마다 기준 턴 오름차순이다. 한 턴의 성공 이벤트는 한 번이라 합계가 그 턴을 저장한 채팅 수다. */
    fun turnReach(segment: String): List<DailyAnalyticsMetric> = splitPlatforms.flatMap { platform -> com.knk.manyak.admin.repository.AdminQuery.turnReachThresholds.map { turn ->
        savedTurns("conversion_turn_reach", platform, segment, AdminMetricEvents.filter("turn_number", listOf(turn.toString())))
    } }
    /** 플랫폼마다 default, persona 클릭 6개 뒤에 모델마다 세 플랫폼의 턴 수가 온다. 모델 코드마다 저장 키가 따로다. */
    fun chatOptions(segment: String, models: List<String>): List<DailyAnalyticsMetric> = splitPlatforms.flatMap { platform -> listOf("default", "persona").map {
        queries.daily("quality_chat_starts", AdminMetricEvents.event(AdminMetricEvents.CHAT_START, filters(platform) + AdminMetricEvents.filter("persona_type", listOf(it)) + AdminMetricEvents.segment(segment)), "totals")
    } } + models.flatMap { model -> splitPlatforms.map { platform -> savedTurns("quality_model_turns", platform, segment, AdminMetricEvents.filter("model_code", listOf(model))) } }
    val qualityEvents = linkedMapOf("chatLoadError" to "client_chat_loadError_shown", "storyListLoadError" to "client_storyList_loadError_shown",
        "storyCompleteError" to "client_storyCreate_completeError_shown", "creditShortage" to "client_creditShortageDialog_shown", "creditChargeViewed" to "client_creditCharge_viewed",
        "attendanceClicked" to "client_creditCharge_attendanceButton_clicked", "inviteSucceeded" to "client_invite_codeInput_succeeded")
    fun quality(platform: String): List<DailyAnalyticsMetric> = when (platform) {
        "all" -> listOf("all", "android", "android_overseas")
        "android" -> listOf("android", "android_overseas")
        else -> listOf(platform)
    }.flatMap { qualityGroup(it) }
    private fun qualityGroup(platform: String): List<DailyAnalyticsMetric> {
        val filters = filters(platform)
        val all = queries.daily("quality_messages", AdminMetricEvents.event(AdminMetricEvents.MESSAGE, filters), "totals")
        val modes = listOf("choice", "plain", "block").map { queries.daily("quality_messages", AdminMetricEvents.event(AdminMetricEvents.MESSAGE, filters + AdminMetricEvents.filter("input_mode", listOf(it))), "totals") }
        val errors = queries.daily("quality_stream_errors", AdminMetricEvents.event("client_chat_streamError_shown", filters), "totals")
        return listOf(all) + modes + errors + qualityEvents.map { (key, event) -> queries.daily("quality_$key", AdminMetricEvents.event(event, filters)) }
    }
}
