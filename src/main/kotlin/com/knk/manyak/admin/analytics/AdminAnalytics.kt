package com.knk.manyak.admin.analytics

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

@ConfigurationProperties("manyak.analytics.amplitude")
class AmplitudeDashboardProperties(
    val apiKey: String = "",
    val secretKey: String = "",
    val teamCohortId: String = "",
    val dashboardBaseUrl: String = "https://amplitude.com",
)

data class DashboardPlatformCounts(val total: Long, val web: Long, val android: Long)
data class AnalyticsDay(val date: LocalDate, val activeUsers: DashboardPlatformCounts, val newUsers: DashboardPlatformCounts)
data class AdminAnalyticsResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val todayActiveUsers: DashboardPlatformCounts?, val daily: List<AnalyticsDay>, val pendingDates: List<LocalDate>)
class AdminAnalyticsUnavailable : RuntimeException("분석 지표를 가져올 수 없습니다.")
enum class AnalyticsFailureReason { no_credentials, http_error, rate_limited, budget_exceeded, timeout }
class AmplitudeFetchFailure(val reason: AnalyticsFailureReason, val status: Int? = null) : RuntimeException("Amplitude dashboard request failed")

@Service
class AdminAnalyticsService(private val snapshots: AnalyticsSnapshots, properties: AmplitudeDashboardProperties, mapper: tools.jackson.databind.ObjectMapper,
    private val clock: Clock = Clock.systemUTC()) {
    private fun metric(name: String, platforms: List<String>, properties: AmplitudeDashboardProperties, mapper: tools.jackson.databind.ObjectMapper): DailyAnalyticsMetric {
        val cohort = if (properties.teamCohortId.isBlank()) emptyList() else listOf("s" to mapper.writeValueAsString(listOf(mapOf("prop" to "userdata_cohort", "op" to "is not", "values" to listOf(properties.teamCohortId)))))
        fun filter(key: String, values: List<String>) = mapOf("subprop_type" to "user", "subprop_key" to key, "subprop_op" to "is", "subprop_value" to values)
        val event = mapper.writeValueAsString(mapOf("event_type" to "_$name", "filters" to listOf(filter("country", listOf("South Korea", "(none)")), filter("platform", platforms))))
        val definition = AnalyticsDefinition("dashboard_$name", "/api/2/events/segmentation", listOf("e" to event, "m" to "uniques", "i" to "1") + cohort, if (cohort.isEmpty()) 1 else 2, mapper)
        return DailyAnalyticsMetric(definition) { data, range -> range.dates().zip(AmplitudeResults.series(data, range).map { mapper.valueToTree<tools.jackson.databind.JsonNode>(it) }).toMap() }
    }
    private val platformFilters = listOf(listOf("Web", "Android"), listOf("Web"), listOf("Android"))
    private val active = platformFilters.map { metric("active", it, properties, mapper) }
    private val new = platformFilters.map { metric("new", it, properties, mapper) }
    fun dailyMetrics(): List<DailyAnalyticsMetric> = active + new
    private fun counts(values: List<AnalyticsValue>): DashboardPlatformCounts {
        check(values.size == 3)
        return DashboardPlatformCounts(values[0].value.asLong(), values[1].value.asLong(), values[2].value.asLong())
    }
    fun dashboard(request: com.knk.manyak.admin.dto.AdminChartsRequest): AdminAnalyticsResponse {
        val requested = request.resolve(clock); val range = AnalyticsRange(requested.from, requested.to)
        val today = clock.instant().atZone(com.knk.manyak.admin.dto.AdminDateRange.KST).toLocalDate()
        val values = snapshots.load("/api/v1/admin/dashboard/analytics", range, dailyMetrics(),
            if (range.to == today) emptyList() else active.map { it to today }, timeout = java.time.Duration.ofSeconds(5))
        val ready = values.readyDates()
        if (ready.isEmpty()) throw AdminAnalyticsUnavailable()
        // 조회 도중 KST 날짜가 바뀌면 전날 값을 오늘 값으로 쓰지 않는다.
        val currentDay = clock.instant().atZone(com.knk.manyak.admin.dto.AdminDateRange.KST).toLocalDate()
        val current = active.mapNotNull { values.value(it, currentDay) }.takeIf { it.size == 3 }
        val used = ready.flatMap { values.values(it) } + current.orEmpty()
        return AdminAnalyticsResponse(values.asOf(used), range.from, range.to, current?.let(::counts),
            ready.map { date -> AnalyticsDay(date, counts(active.map { values.value(it, date)!! }), counts(new.map { values.value(it, date)!! })) }, values.pendingDates())
    }
    fun dashboard(days: Int) = dashboard(com.knk.manyak.admin.dto.AdminChartsRequest().apply { this.days = days.toString() })
}
