package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.admin.dto.badAdminQuery
import org.springframework.stereotype.Service
import java.time.*
import java.util.UUID

class SegmentRangeQuery : AnalyticsRangeQuery() { var segment: String? = null }
class PlatformMetricsQuery : AnalyticsRangeQuery() { var platform: String? = null; var segment: String? = null }
/** 전체, 게스트(user_id 없음), 회원(user_id 있음). 기본 all. */
fun analyticsSegment(value: String?) = (value ?: "all").also { if (it !in setOf("all", "guest", "member")) badAdminQuery("segment가 올바르지 않습니다.") }
class ConversionMetricsQuery : AnalyticsRangeQuery() { var platform: String? = null; var storyId: UUID? = null }
data class PlatformCounts(val all: Long, val web: Long, val android: Long, val unknown: Long)
data class PlatformDecimals(val all: Double?, val web: Double?, val android: Double?, val unknown: Double? = null)
data class ActivityDay(val date: LocalDate, val activeUsers: PlatformCounts, val newVisitors: PlatformCounts, val cumulativeNewVisitors: PlatformCounts, val messagesPerUser: PlatformDecimals)
data class ActivityResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val monthlyActiveUsers: Long?, val daily: List<ActivityDay>, val pendingDates: List<LocalDate>)
data class RetentionRates(val d1: Double?, val d2: Double?, val d3: Double?, val d4: Double?, val d5: Double?, val d6: Double?, val d7: Double?, val d30: Double?)
data class RetentionCohort(val date: LocalDate, val users: Long, val d1: Double?, val d2: Double?, val d3: Double?, val d4: Double?, val d5: Double?, val d6: Double?, val d7: Double?, val d30: Double?)
data class RetentionPlatform(val platform: String, val cohorts: List<RetentionCohort>, val summary: RetentionRates)
data class RetentionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val platforms: List<RetentionPlatform>, val pendingDates: List<LocalDate>)
data class SessionDay(val date: LocalDate, val averageSessionSeconds: PlatformDecimals)
data class SessionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val segment: String, val daily: List<SessionDay>, val pendingDates: List<LocalDate>)
data class QualityDay(val date: LocalDate, val messagesByInputMode: Map<String, Long>, val streamErrors: Long, val streamErrorRate: Double?, val users: Map<String, Long>)
data class QualityResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val platform: String, val segment: String, val daily: List<QualityDay>, val pendingDates: List<LocalDate>)
data class ConversionStep(val event: String, val turnNumber: Int?, val users: Long)
data class ConversionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val funnel: String, val platform: String, val storyId: UUID?, val steps: List<ConversionStep>)

@Service
class AdminMetricsService(private val definitions: MetricDefinitions, private val snapshots: AnalyticsSnapshots, private val queries: AnalyticsQueryFactory,
    private val clock: Clock = Clock.systemUTC()) {
    private fun ready(result: AnalyticsSnapshotResult): List<LocalDate> = result.readyDates().also { if (it.isEmpty()) throw AdminAnalyticsUnavailable() }
    fun activity(query: AnalyticsRangeQuery): ActivityResponse {
        val range = query.range(clock); val metrics = definitions.activity(); val mau = listOf("all", "android", "android_overseas").map { definitions.mau(range.to, it) }
        val result = snapshots.load("users/activity", range, metrics, rangeMetrics = mau); val dates = ready(result)
        val cumulative = LongArray(4)
        val daily = dates.map { date ->
            fun n(i: Int) = result.value(metrics[i], date)!!.value.asLong()
            fun d(i: Int) = result.value(metrics[i], date)!!.value.asDouble()
            val android = Math.addExact(n(4), n(8))
            val new = listOf(Math.addExact(n(1), n(5)), n(3), n(5), n(7))
            for (i in 0..3) cumulative[i] = Math.addExact(cumulative[i], new[i])
            ActivityDay(date, PlatformCounts(Math.addExact(n(0), android), n(2), android, n(6)), PlatformCounts(new[0], new[1], new[2], new[3]),
                PlatformCounts(cumulative[0], cumulative[1], cumulative[2], cumulative[3]), PlatformDecimals(null, d(9), d(10)))
        }
        val monthly = mau.mapNotNull { result.value(it) }.takeIf { it.size == mau.size }
        val monthlyTotal = monthly?.fold(0L) { sum, value -> Math.addExact(sum, value.value.asLong()) }
        return ActivityResponse(result.asOf(dates.flatMap { result.values(it) } + monthly.orEmpty()), range.from, range.to, monthlyTotal, daily, result.pendingDates())
    }
    fun retention(query: AnalyticsRangeQuery): RetentionResponse {
        val range = query.range(clock, default = 7, allowed = setOf(7), endOffset = -1)
        val metrics = definitions.retention(); val result = snapshots.load("users/retention", range, metrics); val dates = ready(result)
        val today = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
        val platforms = metrics.mapIndexed { i, metric ->
            fun point(date: LocalDate, day: Int): tools.jackson.databind.JsonNode? {
                val array = result.value(metric, date)!!.value
                return if (!date.plusDays(day.toLong()).isBefore(today) || array.size() <= day + 1 || array[day + 1]["incomplete"].asBoolean()) null else array[day + 1]
            }
            fun ratio(date: LocalDate, day: Int): Double? = point(date, day)?.let { if (it["outof"].asLong() == 0L) null else it["count"].asDouble() / it["outof"].asDouble() }
            fun weighted(day: Int): Double? {
                val points = dates.mapNotNull { point(it, day) }; val denominator = points.sumOf { it["outof"].asLong() }
                return if (denominator == 0L) null else points.sumOf { it["count"].asLong() }.toDouble() / denominator
            }
            RetentionPlatform(listOf("all", "web", "android")[i], dates.map { date -> RetentionCohort(date, result.value(metric, date)!!.value[0]["outof"].asLong(), ratio(date, 1), ratio(date, 2), ratio(date, 3), ratio(date, 4), ratio(date, 5), ratio(date, 6), ratio(date, 7), ratio(date, 30)) },
                RetentionRates(weighted(1), weighted(2), weighted(3), weighted(4), weighted(5), weighted(6), weighted(7), weighted(30)))
        }
        return RetentionResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, platforms, result.pendingDates())
    }
    fun sessions(query: SegmentRangeQuery): SessionResponse {
        val range = query.range(clock, default = 14, allowed = setOf(7, 14)); val segment = analyticsSegment(query.segment); val metrics = definitions.sessions(segment)
        val result = snapshots.load("users/sessions", range, metrics); val dates = ready(result)
        return SessionResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, segment, dates.map { date ->
            val values = metrics.map { result.value(it, date)!!.value.asDouble() }; SessionDay(date, PlatformDecimals(values[0], values[1], values[2]))
        }, result.pendingDates())
    }
    fun quality(query: PlatformMetricsQuery): QualityResponse {
        val platform = query.platform ?: "all"; if (platform !in setOf("all", "web", "android")) badAdminQuery("platform이 올바르지 않습니다.")
        val segment = analyticsSegment(query.segment); val range = query.range(clock); val metrics = definitions.quality(platform, segment)
        val result = snapshots.load("quality/client", range, metrics); val dates = ready(result)
        return QualityResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, platform, segment, dates.map { date ->
            // 구성 조회 하나라도 없으면 readyDates에서 제외한다. 빠진 묶음을 0으로 간주하지 않는다.
            val v = (0 until 12).map { index -> metrics.indices.filter { it % 12 == index }.fold(0L) { sum, i -> Math.addExact(sum, result.value(metrics[i], date)!!.value.asLong()) } }
            QualityDay(date, linkedMapOf("choice" to v[1], "plain" to v[2], "block" to v[3], "unknown" to v[0] - v[1] - v[2] - v[3]), v[4],
                if (v[0] == 0L) null else v[4].toDouble() / v[0], definitions.qualityEvents.keys.zip(v.drop(5)).toMap())
        }, result.pendingDates())
    }
    fun funnel(name: String, query: ConversionMetricsQuery): ConversionResponse {
        val fixed = when (name) { "web-first-visit" -> "web"; "android-first-visit" -> "android"; "story-creation", "story-detail-chat", "guest-limit-login" -> null; else -> badAdminQuery("funnel이 올바르지 않습니다.") }
        if ((fixed != null && query.platform != null) || (name != "story-detail-chat" && query.storyId != null)) badAdminQuery("퍼널이 받지 않는 쿼리입니다.")
        val platform = fixed ?: query.platform ?: "web"; if (platform !in setOf("web", "android")) badAdminQuery("platform이 올바르지 않습니다.")
        val range = query.range(clock, 31, 7)
        val names = when (name) {
            "web-first-visit" -> AdminMetricEvents.creation
            "android-first-visit" -> listOf("client_login_viewed", AdminMetricEvents.MESSAGE)
            "story-creation" -> listOf(AdminMetricEvents.GENERATE, "client_storyCreate_storylineOption_selected", AdminMetricEvents.COMPLETE, "client_storyCreate_completed")
            "story-detail-chat" -> listOf("client_storyDetail_viewed", "client_storyDetail_chatStartButton_clicked") + List(5) { AdminMetricEvents.MESSAGE }
            else -> listOf("client_guestLimitDialog_shown", "client_guestLimitDialog_loginButton_clicked", AdminMetricEvents.GOOGLE)
        }
        fun definition(events: List<String>) = queries.funnel("conversion_$name", range, events.mapIndexed { i, event ->
            val extra = if (name != "story-detail-chat") emptyList() else when {
                i < 2 && query.storyId != null -> listOf(AdminMetricEvents.filter("story_id", listOf(query.storyId.toString())))
                i >= 2 -> listOf(AdminMetricEvents.filter("turn_number", listOf((i - 1).toString())))
                else -> emptyList()
            }
            AdminMetricEvents.event(event, definitions.filters(platform) + extra)
        })
        val metrics = listOf(definition(names)) + if (name == "guest-limit-login") listOf(definition(names.dropLast(1) + AdminMetricEvents.KAKAO)) else emptyList()
        val result = snapshots.load("conversion/funnels/$name", range, emptyList(), rangeMetrics = metrics)
        val values = metrics.map { result.value(it) ?: throw AdminAnalyticsUnavailable() }
        val steps = names.mapIndexed { i, event -> ConversionStep(if (name == "guest-limit-login" && i == 2) "login_succeeded" else event,
            if (name == "story-detail-chat" && i >= 2) i - 1 else null,
            if (name == "guest-limit-login" && i == 2) Math.addExact(values[0].value[i].asLong(), values[1].value[i].asLong()) else values[0].value[i].asLong()) }
        return ConversionResponse(result.asOf(values), range.from, range.to, name, platform, query.storyId, steps)
    }
}
