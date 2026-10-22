package com.knk.manyak.admin.analytics

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
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

data class AnalyticsDay(val date: LocalDate, val activeUsers: Long, val newUsers: Long)
data class AdminAnalyticsResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate,
    val todayActiveUsers: Long, val daily: List<AnalyticsDay>)
data class AmplitudeSeries(val active: List<Long>, val new: List<Long>, val todayActive: Long)

class AdminAnalyticsUnavailable : RuntimeException("분석 지표를 가져올 수 없습니다.")
enum class AnalyticsFailureReason { no_credentials, http_error, rate_limited, timeout }
class AmplitudeFetchFailure(val reason: AnalyticsFailureReason, val status: Int? = null) : RuntimeException("Amplitude dashboard request failed")

@Component
@EnableConfigurationProperties(AmplitudeDashboardProperties::class)
class AmplitudeDashboardClient(private val properties: AmplitudeDashboardProperties, private val mapper: tools.jackson.databind.ObjectMapper) {
    private val http = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
        .build()

    /** 연결·본문 수신을 포함해 두 조회 전체가 하나의 5초 예산을 공유한다. 재시도하지 않는다. */
    fun fetch(from: LocalDate, to: LocalDate, today: LocalDate): AmplitudeSeries {
        if (properties.apiKey.isBlank() || properties.secretKey.isBlank()) throw AmplitudeFetchFailure(AnalyticsFailureReason.no_credentials)
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos()
        val active = query("active", from, to, deadline)
        val new = query("new", from, to, deadline)
        return AmplitudeSeries(active, new, if (to == today) active.last() else query("active", today, today, deadline).single())
    }

    private fun query(metric: String, from: LocalDate, to: LocalDate, deadline: Long): List<Long> {
        var pending: java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>>? = null
        var status: Int? = null
        try {
            val params = linkedMapOf("m" to metric, "i" to "1", "start" to from.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
                "end" to to.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
            properties.teamCohortId.takeIf { it.isNotBlank() }?.let { cohort ->
                params["s"] = mapper.writeValueAsString(listOf(mapOf("prop" to "userdata_cohort", "op" to "is not", "values" to listOf(cohort))))
            }
            val query = params.entries.joinToString("&") { (key, value) -> key + "=" + java.net.URLEncoder.encode(value, Charsets.UTF_8) }
            val base = properties.dashboardBaseUrl.ifBlank { "https://amplitude.com" }.trimEnd('/')
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw AmplitudeFetchFailure(AnalyticsFailureReason.timeout)
            val basic = java.util.Base64.getEncoder().encodeToString((properties.apiKey + ":" + properties.secretKey).toByteArray(Charsets.UTF_8))
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("$base/api/2/users?$query"))
                .timeout(java.time.Duration.ofNanos(remaining)).header("Authorization", "Basic $basic").header("Accept", "application/json").GET().build()
            pending = http.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            val response = pending.get((deadline - System.nanoTime()).coerceAtLeast(1), java.util.concurrent.TimeUnit.NANOSECONDS)
            status = response.statusCode()
            if (status !in 200..299) throw AmplitudeFetchFailure(if (status == 429) AnalyticsFailureReason.rate_limited else AnalyticsFailureReason.http_error, status)
            val data = mapper.readTree(response.body()).path("data")
            val dates = data.path("xValues")
            val groups = data.path("series")
            val expected = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1
            require(dates.isArray && dates.size() == expected && groups.isArray && groups.size() == 1)
            val values = groups[0]
            require(values.isArray && values.size() == expected)
            // 각 값은 xValues의 같은 인덱스 날짜에 대응한다. seriesMeta는 그룹 이름이며 사용자 수로 해석하지 않는다.
            val byDate = linkedMapOf<LocalDate, Long>()
            dates.forEachIndexed { index, node ->
                require(node.isString)
                val date = LocalDate.parse(node.asText())
                val value = values[index]
                require(value.isIntegralNumber && value.canConvertToLong() && value.asLong() >= 0 && date !in byDate)
                byDate[date] = value.asLong()
            }
            return (0 until expected).map { offset -> requireNotNull(byDate[from.plusDays(offset.toLong())]) }
        } catch (failure: AmplitudeFetchFailure) {
            throw failure
        } catch (_: java.util.concurrent.TimeoutException) {
            throw AmplitudeFetchFailure(AnalyticsFailureReason.timeout)
        } catch (failure: java.util.concurrent.ExecutionException) {
            throw AmplitudeFetchFailure(if (failure.cause is java.net.http.HttpTimeoutException) AnalyticsFailureReason.timeout else AnalyticsFailureReason.http_error, status)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error, status)
        } catch (_: Exception) {
            // JSON 예외는 외부 응답 본문을 포함할 수 있다. 원인·메시지·인증 헤더를 전파하지 않는다.
            throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error, status)
        } finally {
            pending?.takeUnless { it.isDone }?.cancel(true)
        }
    }
}

@Service
class AdminAnalyticsService(private val client: AmplitudeDashboardClient, private val clock: Clock = Clock.systemUTC()) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    private data class CachedResponse(val response: AdminAnalyticsResponse, val today: LocalDate)
    private class Cache {
        @Volatile var value: CachedResponse? = null
        @Volatile var retryAfter: Instant = Instant.MIN
        val refreshing = java.util.concurrent.atomic.AtomicBoolean()
    }
    private val ranges = linkedMapOf<com.knk.manyak.admin.dto.AdminDashboardRange, Cache>()

    fun dashboard(request: com.knk.manyak.admin.dto.AdminChartsRequest): AdminAnalyticsResponse {
        val range = request.resolve(clock)
        val state = synchronized(ranges) {
            ranges[range] ?: Cache().also {
                // 임의 범위 조합으로 프로세스 캐시가 계속 늘어나지 않게 제한한다.
                if (ranges.size >= 32) ranges.remove(ranges.keys.first())
                ranges[range] = it
            }
        }
        return load(range, state)
    }

    fun dashboard(days: Int): AdminAnalyticsResponse = dashboard(com.knk.manyak.admin.dto.AdminChartsRequest().apply { this.days = days.toString() })

    private fun load(range: com.knk.manyak.admin.dto.AdminDashboardRange, state: Cache): AdminAnalyticsResponse {
        fun fresh(value: CachedResponse?): Boolean {
            if (value == null) return false
            val now = clock.instant()
            return value.response.from == range.from && value.response.to == range.to &&
                value.today == now.atZone(com.knk.manyak.admin.dto.AdminDateRange.KST).toLocalDate() &&
                now.isBefore(value.response.asOf.plusSeconds(600))
        }
        fun fallback() = state.value?.response ?: throw AdminAnalyticsUnavailable()
        if (fresh(state.value)) return state.value!!.response
        if (clock.instant().isBefore(state.retryAfter) || !state.refreshing.compareAndSet(false, true)) return fallback()
        try {
            if (fresh(state.value) || clock.instant().isBefore(state.retryAfter)) return fallback()
            val today = clock.instant().atZone(com.knk.manyak.admin.dto.AdminDateRange.KST).toLocalDate()
            val data = client.fetch(range.from, range.to, today)
            val daily = data.active.indices.map { i -> AnalyticsDay(range.from.plusDays(i.toLong()), data.active[i], data.new[i]) }
            val full = AdminAnalyticsResponse(clock.instant(), range.from, range.to, data.todayActive, daily)
            // 조회에 사용한 날짜와 응답을 함께 게시한다. 자정을 넘겨 완료돼도 전날 값은 신선하지 않다.
            state.value = CachedResponse(full, today)
            state.retryAfter = Instant.MIN
            return full
        } catch (failure: AmplitudeFetchFailure) {
            state.retryAfter = clock.instant().plusSeconds(60)
            log.warn("admin_analytics_fetch_failed reason={} status={}", failure.reason.name, failure.status)
            return fallback()
        } finally {
            state.refreshing.set(false)
        }
    }
}
