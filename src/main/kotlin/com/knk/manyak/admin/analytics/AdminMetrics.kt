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
data class MetricPlatformCounts(val all: Long, val web: Long, val android: Long)
data class PlatformDecimals(val all: Double?, val web: Double?, val android: Double?, val unknown: Double? = null)
data class ActivityDay(val date: LocalDate, val activeUsers: PlatformCounts, val newVisitors: PlatformCounts, val cumulativeNewVisitors: PlatformCounts, val messagesPerUser: PlatformDecimals)
data class ActivityResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val monthlyActiveUsers: Long?, val daily: List<ActivityDay>, val pendingDates: List<LocalDate>)
data class RetentionRates(val d1: Double?, val d2: Double?, val d3: Double?, val d4: Double?, val d5: Double?, val d6: Double?, val d7: Double?, val d30: Double?)
data class RetentionCohort(val date: LocalDate, val users: Long, val d1: Double?, val d2: Double?, val d3: Double?, val d4: Double?, val d5: Double?, val d6: Double?, val d7: Double?, val d30: Double?)
data class RetentionPlatform(val platform: String, val cohorts: List<RetentionCohort>, val summary: RetentionRates)
class RetentionQuery : AnalyticsRangeQuery() { var cohort: String? = null }
data class RetentionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val cohort: String, val platforms: List<RetentionPlatform>, val pendingDates: List<LocalDate>)
data class SessionDay(val date: LocalDate, val averageSessionSeconds: PlatformDecimals)
data class SessionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val segment: String, val daily: List<SessionDay>, val pendingDates: List<LocalDate>)
data class SessionLengthBin(val fromSeconds: Int, val toSeconds: Int)
data class SessionLengthPlatform(val platform: String, val sessions: List<Long>, val totalSessions: Long, val medianBin: Int?)
data class SessionLengthResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val segment: String, val pendingDates: List<LocalDate>, val bins: List<SessionLengthBin>, val platforms: List<SessionLengthPlatform>)
data class QualityDay(val date: LocalDate, val messagesByInputMode: Map<String, Long>, val streamErrors: Long, val streamErrorRate: Double?, val users: Map<String, Long>)
data class StoryCompletionDay(val date: LocalDate, val requests: Long, val failed: Long, val failureRate: Double?)
data class QualityResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val platform: String, val segment: String, val daily: List<QualityDay>, val pendingDates: List<LocalDate>,
    val storyCompletion: List<StoryCompletionDay>)
data class ConversionStep(val event: String, val turnNumber: Int?, val users: Long)
data class ConversionResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val funnel: String, val platform: String, val storyId: UUID?, val steps: List<ConversionStep>)

@Service
class AdminMetricsService(private val definitions: MetricDefinitions, private val snapshots: AnalyticsSnapshots, private val queries: AnalyticsQueryFactory,
    private val reads: AdminMetricReadRepository, private val dashboard: com.knk.manyak.admin.config.AdminDashboardProperties, private val clock: Clock = Clock.systemUTC()) {
    private fun ready(result: AnalyticsSnapshotResult): List<LocalDate> = result.readyDates().also { if (it.isEmpty()) throw AdminAnalyticsUnavailable() }
    fun activity(query: AnalyticsRangeQuery): ActivityResponse {
        val range = query.range(clock); val metrics = definitions.activity(); val mau = listOf(definitions.mau(range.to))
        val result = snapshots.load("users/activity", range, metrics, rangeMetrics = mau); val dates = ready(result)
        val cumulative = LongArray(4)
        val daily = dates.map { date ->
            fun n(i: Int) = result.value(metrics[i], date)!!.value.asLong()
            fun d(i: Int) = result.value(metrics[i], date)!!.value.asDouble()
            val new = listOf(n(1), n(3), n(5), n(7))
            for (i in 0..3) cumulative[i] = Math.addExact(cumulative[i], new[i])
            ActivityDay(date, PlatformCounts(n(0), n(2), n(4), n(6)), PlatformCounts(new[0], new[1], new[2], new[3]),
                PlatformCounts(cumulative[0], cumulative[1], cumulative[2], cumulative[3]), PlatformDecimals(null, d(8), d(9)))
        }
        val monthly = mau.mapNotNull { result.value(it) }.takeIf { it.size == mau.size }
        val monthlyTotal = monthly?.fold(0L) { sum, value -> Math.addExact(sum, value.value.asLong()) }
        return ActivityResponse(result.asOf(dates.flatMap { result.values(it) } + monthly.orEmpty()), range.from, range.to, monthlyTotal, daily, result.pendingDates())
    }
    fun retention(query: AnalyticsRangeQuery, cohort: String? = null): RetentionResponse {
        val start = (cohort ?: "visitor").also { if (it !in setOf("visitor", "signup", "first-chat")) badAdminQuery("cohort가 올바르지 않습니다.") }
        val range = query.range(clock, default = 7, allowed = setOf(7), endOffset = -1)
        val metrics = definitions.retention(start); val result = snapshots.load("users/retention", range, metrics); val dates = ready(result)
        val today = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
        // 가입자는 구글과 카카오 두 배열을 인덱스마다 더하고 하나라도 집계 중이면 집계 중이다. 짧은 쪽 길이까지만 둔다.
        data class Point(val count: Long, val outof: Long, val incomplete: Boolean)
        val platforms = metrics.chunked(metrics.size / 3).mapIndexed { i, group ->
            val cohorts = dates.associateWith { date ->
                val arrays = group.map { result.value(it, date)!!.value }
                (0 until arrays.minOf { it.size() }).map { index -> arrays.map { it[index] }.let { p ->
                    Point(p.sumOf { it["count"].asLong() }, p.sumOf { it["outof"].asLong() }, p.any { it["incomplete"].asBoolean() }) } }
            }
            fun point(date: LocalDate, day: Int): Point? {
                val array = cohorts.getValue(date)
                return if (!date.plusDays(day.toLong()).isBefore(today) || array.size <= day + 1 || array[day + 1].incomplete) null else array[day + 1]
            }
            fun ratio(date: LocalDate, day: Int): Double? = point(date, day)?.let { if (it.outof == 0L) null else it.count.toDouble() / it.outof }
            fun weighted(day: Int): Double? {
                val points = dates.mapNotNull { point(it, day) }; val denominator = points.sumOf { it.outof }
                return if (denominator == 0L) null else points.sumOf { it.count }.toDouble() / denominator
            }
            RetentionPlatform(listOf("all", "web", "android")[i], dates.map { date -> RetentionCohort(date, cohorts.getValue(date)[0].outof, ratio(date, 1), ratio(date, 2), ratio(date, 3), ratio(date, 4), ratio(date, 5), ratio(date, 6), ratio(date, 7), ratio(date, 30)) },
                RetentionRates(weighted(1), weighted(2), weighted(3), weighted(4), weighted(5), weighted(6), weighted(7), weighted(30)))
        }
        return RetentionResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, start, platforms, result.pendingDates())
    }
    fun sessionLengths(query: SegmentRangeQuery): SessionLengthResponse {
        val range = query.range(clock, default = 14, allowed = setOf(7, 14)); val segment = analyticsSegment(query.segment); val metrics = definitions.sessionLengths(segment)
        val result = snapshots.load("users/session-lengths", range, metrics); val dates = ready(result)
        val platforms = metrics.mapIndexed { i, metric ->
            val sessions = (0 until 9).map { bin -> dates.fold(0L) { sum, date -> Math.addExact(sum, result.value(metric, date)!!.value[bin].asLong()) } }
            val total = sessions.sum()
            // 짧은 구간부터 더한 세션 수가 처음으로 전체의 절반 이상이 되는 구간이다.
            val median = if (total == 0L) null else sessions.runningReduce(Long::plus).indexOfFirst { it * 2 >= total }
            SessionLengthPlatform(listOf("all", "web", "android")[i], sessions, total, median)
        }
        return SessionLengthResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, segment, result.pendingDates(),
            definitions.sessionLengthBounds.zipWithNext(::SessionLengthBin), platforms)
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
        val group = 5 + definitions.qualityEvents.size
        // 간편 제작 실패율은 서버 DB 값이라 platform, segment, 준비 중 날짜와 무관하게 범위의 모든 날짜를 채운다.
        val completions = reads.storyCompletions(range, dashboard.excludedUserIds).associateBy { LocalDate.ofEpochDay((it["metric_day"] as Number).toLong()) }
        return QualityResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, platform, segment, dates.map { date ->
            // 구성 조회 하나라도 없으면 readyDates에서 제외한다. 빠진 묶음을 0으로 간주하지 않는다.
            val v = (0 until group).map { index -> metrics.indices.filter { it % group == index }.fold(0L) { sum, i -> Math.addExact(sum, result.value(metrics[i], date)!!.value.asLong()) } }
            QualityDay(date, linkedMapOf("choice" to v[1], "plain" to v[2], "block" to v[3], "unknown" to v[0] - v[1] - v[2] - v[3]), v[4],
                if (v[0] == 0L) null else v[4].toDouble() / v[0], definitions.qualityEvents.keys.zip(v.drop(5)).toMap())
        }, result.pendingDates(), range.dates().map { date ->
            val row = completions[date]; val failed = (row?.get("failed") as Number?)?.toLong() ?: 0L
            val requests = Math.addExact((row?.get("completed") as Number?)?.toLong() ?: 0L, failed)
            StoryCompletionDay(date, requests, failed, if (requests == 0L) null else failed.toDouble() / requests)
        })
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
        }, users = if (name == "web-first-visit") "new" else "active")
        val metrics = listOf(definition(names)) + if (name == "guest-limit-login") listOf(definition(names.dropLast(1) + AdminMetricEvents.KAKAO)) else emptyList()
        val result = snapshots.load("conversion/funnels/$name", range, emptyList(), rangeMetrics = metrics)
        val values = metrics.map { result.value(it) ?: throw AdminAnalyticsUnavailable() }
        val steps = names.mapIndexed { i, event -> ConversionStep(if (name == "guest-limit-login" && i == 2) "login_succeeded" else event,
            if (name == "story-detail-chat" && i >= 2) i - 1 else null,
            if (name == "guest-limit-login" && i == 2) Math.addExact(values[0].value[i].asLong(), values[1].value[i].asLong()) else values[0].value[i].asLong()) }
        return ConversionResponse(result.asOf(values), range.from, range.to, name, platform, query.storyId, steps)
    }
}
