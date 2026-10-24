package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.concurrent.*

@ConfigurationProperties("manyak.admin.langfuse")
class AdminLangfuseProperties(
    val host: String = "",
    val publicKey: String = "",
    val secretKey: String = "",
    val environment: String = "",
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val callInterval: Duration = Duration.ofSeconds(1),
    val todayTtl: Duration = Duration.ofMinutes(10),
    val yesterdayTtl: Duration = Duration.ofHours(1),
    val cooldown: Duration = Duration.ofSeconds(60),
    val maxCalls: Int = 6,
    val maxDays: Int = 3,
    val rowLimit: Int = 1000,
    val retentionDays: Int = 30,
) {
    init {
        require(listOf(requestTimeout, callInterval, todayTtl, yesterdayTtl, cooldown).all { !it.isNegative && !it.isZero })
        require(retentionDays >= 2)
        require(maxCalls in 1..6 && maxDays in 1..3 && rowLimit == 1000)
    }
    fun configured() = listOf(host, publicKey, secretKey, environment).all { it.isNotBlank() }
}

data class LangfuseCostEntry(val traceName: String, val model: String?, val costUsd: BigDecimal, val totalTokens: Long)
data class LangfuseCostDay(val entries: List<LangfuseCostEntry>, val fetchedAt: Instant)
data class LangfuseMetricsPage(val entries: Map<LocalDate, List<LangfuseCostEntry>>, val rowLimitReached: Boolean = false)

/** 시작 포함·끝 제외. 분할 경계를 시간 단위에 맞춰 같은 구간을 중복 집계하지 않는다. */
data class LangfuseInterval(val from: Instant, val until: Instant) {
    constructor(range: AnalyticsRange) : this(range.from.atStartOfDay(AdminDateRange.KST).toInstant(), range.to.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant())
    init {
        require(from < until && from == from.truncatedTo(ChronoUnit.HOURS) && until == until.truncatedTo(ChronoUnit.HOURS))
    }
    val hours: Long get() = Duration.between(from, until).toHours()
    fun dates() = AnalyticsRange(from.atZone(AdminDateRange.KST).toLocalDate(), until.minusMillis(1).atZone(AdminDateRange.KST).toLocalDate()).dates()
    fun split(): Pair<LangfuseInterval, LangfuseInterval> {
        require(hours > 1)
        val middle = from.plus(hours / 2, ChronoUnit.HOURS)
        return LangfuseInterval(from, middle) to LangfuseInterval(middle, until)
    }
}
class AdminAiCostUnavailable : RuntimeException("AI 비용을 가져올 수 없습니다.")
enum class AiCostFailureReason { not_configured, http_error, rate_limited, timeout, parse_error }
class LangfuseFetchFailure(val reason: AiCostFailureReason, val status: Int? = null, val retryAfter: Duration? = null) :
    RuntimeException("Langfuse metrics request failed")

@Component
@EnableConfigurationProperties(AdminLangfuseProperties::class)
class LangfuseMetricsClient(private val properties: AdminLangfuseProperties, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(properties.requestTimeout).followRedirects(HttpClient.Redirect.NEVER).build()
    // v2: https://langfuse.com/docs/metrics/features/metrics-api
    // 1,000행 응답은 잘렸을 수 있으므로 스냅샷 계층에서 구간을 나눠 재조회한다.
    private val query = linkedMapOf<String, Any>(
        "view" to "observations",
        "dimensions" to listOf(mapOf("field" to "traceName"), mapOf("field" to "providedModelName")),
        "metrics" to listOf(mapOf("measure" to "totalCost", "aggregation" to "sum"), mapOf("measure" to "totalTokens", "aggregation" to "sum")),
        "timeDimension" to mapOf("granularity" to "hour"),
        "filters" to listOf(
            mapOf("column" to "environment", "operator" to "=", "value" to properties.environment, "type" to "string"),
            mapOf("column" to "name", "operator" to "none of", "value" to listOf("이미지 생성:자식"), "type" to "stringOptions"),
        ),
        "orderBy" to listOf(mapOf("field" to "time_dimension", "direction" to "asc")),
        "config" to mapOf("row_limit" to properties.rowLimit),
    )
    // 공개 키는 해시에만 넣는다. 비밀 키, 환율, 표시 매핑은 원본 식별과 무관하다.
    val definition = AnalyticsDefinition("langfuse_ai_cost", "/api/public/v2/metrics", listOf(
        "source" to properties.host.trimEnd('/'), "environment" to properties.environment, "query" to mapper.writeValueAsString(query),
    ), 0, mapper, hashParameters = listOf("publicKey" to properties.publicKey))

    fun fetch(interval: LangfuseInterval, now: Instant): LangfuseMetricsPage {
        if (!properties.configured()) throw LangfuseFetchFailure(AiCostFailureReason.not_configured)
        // toTimestamp는 포함 경계다. 오늘도 논리 범위의 끝을 보내며 현재까지 수집된 관측을 받는다.
        val json = mapper.writeValueAsString(query + mapOf("fromTimestamp" to interval.from.toString(), "toTimestamp" to interval.until.minusMillis(1).toString()))
        var pending: CompletableFuture<HttpResponse<String>>? = null
        var status: Int? = null
        try {
            val basic = Base64.getEncoder().encodeToString("${properties.publicKey}:${properties.secretKey}".toByteArray(Charsets.UTF_8))
            val request = HttpRequest.newBuilder(URI.create(properties.host.trimEnd('/') + definition.path + "?query=" + URLEncoder.encode(json, Charsets.UTF_8)))
                .timeout(properties.requestTimeout).header("Authorization", "Basic $basic").header("Accept", "application/json").GET().build()
            pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            // HttpRequest.timeout만으로는 응답 본문의 무한 지연을 제한하지 못하므로 전체 완료를 제한한다.
            val response = pending.get(properties.requestTimeout.toNanos(), TimeUnit.NANOSECONDS)
            status = response.statusCode()
            if (status !in 200..299) throw LangfuseFetchFailure(
                if (status == 429) AiCostFailureReason.rate_limited else AiCostFailureReason.http_error, status,
                if (status == 429) retryAfter(response.headers().firstValue("Retry-After").orElse(null), now) else null,
            )
            return try { decode(mapper.readTree(response.body()), interval) }
            catch (_: Exception) { throw LangfuseFetchFailure(AiCostFailureReason.parse_error, status) }
        } catch (failure: LangfuseFetchFailure) { throw failure }
        catch (_: TimeoutException) { throw LangfuseFetchFailure(AiCostFailureReason.timeout, status) }
        catch (failure: ExecutionException) { throw LangfuseFetchFailure(if (failure.cause is HttpTimeoutException) AiCostFailureReason.timeout else AiCostFailureReason.http_error, status) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw LangfuseFetchFailure(AiCostFailureReason.http_error, status) }
        catch (_: Exception) { throw LangfuseFetchFailure(AiCostFailureReason.http_error, status) }
        finally { pending?.takeUnless { it.isDone }?.cancel(true) }
    }

    private fun retryAfter(value: String?, now: Instant): Duration? = try {
        value?.trim()?.let { text -> text.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
            ?: Duration.between(now, ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).coerceAtLeast(Duration.ZERO) }
    } catch (_: Exception) { null }

    internal fun decode(root: JsonNode, interval: LangfuseInterval): LangfuseMetricsPage {
        val data = requireNotNull(root["data"])
        require(data.isArray)
        require(data.size() <= properties.rowLimit)
        if (data.size() == properties.rowLimit) return LangfuseMetricsPage(emptyMap(), rowLimitReached = true)
        val byDate = interval.dates().associateWith { linkedMapOf<Pair<String, String?>, LangfuseCostEntry>() }
        for (row in data) {
            require(row.isObject)
            val time = requireNotNull(row["time_dimension"]).also { require(it.isString) }
            val instant = Instant.parse(time.asText())
            if (instant < interval.from || instant >= interval.until) continue
            require(instant == instant.truncatedTo(ChronoUnit.HOURS))
            val date = instant.atZone(AdminDateRange.KST).toLocalDate()
            val bucket = requireNotNull(byDate[date])
            val trace = requireNotNull(row["traceName"]).also { require(it.isString || it.isNull) }.let { if (it.isNull) "" else it.asText() }
            val model = requireNotNull(row["providedModelName"]).also { require(it.isString || it.isNull) }.let { if (it.isNull) null else it.asText() }
            val cost = decimal(requireNotNull(row["sum_totalCost"]))
            val tokens = decimal(requireNotNull(row["sum_totalTokens"])).longValueExact()
            val key = trace to model
            val previous = bucket[key]
            bucket[key] = LangfuseCostEntry(trace, model, cost + (previous?.costUsd ?: BigDecimal.ZERO), Math.addExact(tokens, previous?.totalTokens ?: 0L))
        }
        return LangfuseMetricsPage(byDate.mapValues { it.value.values.toList() })
    }
    private fun decimal(value: JsonNode): BigDecimal {
        require(value.isNumber || value.isString)
        return value.asText().toBigDecimal().also { require(it.signum() >= 0) }
    }
}
