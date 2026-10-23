package com.knk.manyak.admin.analytics

import org.springframework.stereotype.Component
import org.springframework.boot.context.properties.EnableConfigurationProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Component
@EnableConfigurationProperties(AmplitudeDashboardProperties::class)
class AmplitudeRestClient(private val properties: AmplitudeDashboardProperties, private val mapper: ObjectMapper, private val budget: AmplitudeBudget,
    private val policy: AnalyticsPolicy = AnalyticsPolicy()) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private data class Response(val data: JsonNode, val status: Int)
    fun fetch(def: AnalyticsDefinition, range: AnalyticsRange, deadline: Long, onSend: () -> Unit = {}): JsonNode = fetchResponse(def, range, deadline, onSend).data
    fun <T> fetchDecoded(def: AnalyticsDefinition, range: AnalyticsRange, deadline: Long, onSend: () -> Unit = {}, decode: (JsonNode) -> T): T {
        val response = fetchResponse(def, range, deadline, onSend)
        return try { decode(response.data) }
        catch (failure: AmplitudeFetchFailure) {
            throw AmplitudeFetchFailure(failure.reason, failure.status ?: response.status,
                failure.exceptionClass ?: failure.javaClass.name, failure.errorCode, "result_decode")
        } catch (failure: Exception) {
            throw AmplitudeFetchFailure(AnalyticsFailureReason.parse_error, response.status, failure.javaClass.name, phase = "result_decode")
        }
    }
    private fun errorCode(body: String): String? = runCatching {
        val root = mapper.readTree(body)
        val code = root["code"] ?: root["error_code"] ?: root["error"]?.takeIf { it.isObject }?.get("code")
        code?.takeIf { it.isString || it.isIntegralNumber }?.asText()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }
    }.getOrNull()
    private fun fetchResponse(def: AnalyticsDefinition, range: AnalyticsRange, deadline: Long, onSend: () -> Unit): Response {
        if (properties.apiKey.isBlank() || properties.secretKey.isBlank()) throw AmplitudeFetchFailure(AnalyticsFailureReason.no_credentials)
        budget.enter(deadline)
        var pending: java.util.concurrent.CompletableFuture<HttpResponse<String>>? = null
        var status: Int? = null
        var phase = "request"
        try {
            val queryDeadline = minOf(deadline, System.nanoTime() + Duration.ofSeconds(policy.requestTimeoutSeconds).toNanos())
            val remaining = queryDeadline - System.nanoTime()
            if (remaining <= 0) throw AmplitudeFetchFailure(AnalyticsFailureReason.timeout)
            val params = def.parameters + listOf("start" to range.from.format(DateTimeFormatter.BASIC_ISO_DATE), "end" to range.to.format(DateTimeFormatter.BASIC_ISO_DATE))
            val qs = params.joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }
            val basic = java.util.Base64.getEncoder().encodeToString((properties.apiKey + ":" + properties.secretKey).toByteArray(Charsets.UTF_8))
            val request = HttpRequest.newBuilder(URI.create(properties.dashboardBaseUrl.ifBlank { "https://amplitude.com" }.trimEnd('/') + def.path + "?" + qs))
                .timeout(Duration.ofNanos(remaining)).header("Authorization", "Basic $basic").header("Accept", "application/json").GET().build()
            onSend()
            pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            val response = pending.get((queryDeadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
            status = response.statusCode()
            if (status !in 200..299) throw AmplitudeFetchFailure(if (status == 429) AnalyticsFailureReason.rate_limited else AnalyticsFailureReason.http_error,
                status, errorCode = errorCode(response.body()), phase = "http_status")
            phase = "response_decode"
            val data = requireNotNull(mapper.readTree(response.body())["data"])
            require(!data.isNull)
            return Response(data, status)
        } catch (failure: AmplitudeFetchFailure) { throw failure }
        catch (failure: java.util.concurrent.TimeoutException) { throw AmplitudeFetchFailure(AnalyticsFailureReason.timeout, status, failure.javaClass.name, phase = phase) }
        catch (failure: java.util.concurrent.ExecutionException) {
            val cause = failure.cause ?: failure
            throw AmplitudeFetchFailure(if (cause is HttpTimeoutException) AnalyticsFailureReason.timeout else AnalyticsFailureReason.http_error, status, cause.javaClass.name, phase = phase)
        }
        catch (failure: InterruptedException) { Thread.currentThread().interrupt(); throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error, status, failure.javaClass.name, phase = phase) }
        catch (failure: Exception) { throw AmplitudeFetchFailure(if (phase == "response_decode") AnalyticsFailureReason.parse_error else AnalyticsFailureReason.http_error, status, failure.javaClass.name, phase = phase) }
        finally { pending?.takeUnless { it.isDone }?.cancel(true); budget.leave() }
    }
}

/** Amplitude 문서의 일별 series·그룹별 seriesCollapsed·퍼널 cumulativeRaw를 검증한다. */
object AmplitudeResults {
    /** Amplitude의 프로젝트 날짜 라벨은 날짜 또는 ISO 날짜시각이며 offset으로 날짜를 변환하지 않는다. */
    fun date(node: JsonNode): LocalDate {
        require(node.isString)
        val raw = node.asText()
        return if (raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) LocalDate.parse(raw)
            else LocalDate.from(DateTimeFormatter.ISO_DATE_TIME.parse(raw))
    }
    fun number(node: JsonNode): Long { require(node.isIntegralNumber && node.canConvertToLong() && node.asLong() >= 0); return node.asLong() }
    fun series(data: JsonNode, range: AnalyticsRange): List<Long> = parse {
        val dates = data["xValues"]; val values = data["series"]
        require(dates != null && dates.isArray && dates.size() == range.days && values != null && values.isArray && values.size() == 1)
        require(values[0].isArray && values[0].size() == range.days)
        val keyed = linkedMapOf<LocalDate, Long>()
        dates.forEachIndexed { index, date -> require(date.isString); require(keyed.put(date(date), number(values[0][index])) == null) }
        range.dates().map { requireNotNull(keyed[it]) }
    }
    fun funnel(data: JsonNode, steps: Int): List<Long> = parse {
        require(data.isArray)
        if (data.isEmpty) return@parse List(steps) { 0L }
        require(data.size() == 1)
        val values = data[0]["cumulativeRaw"]; require(values != null && values.isArray && values.size() == steps)
        values.toList().map(::number)
    }
    data class Group(val labels: List<String?>, val value: Long)
    fun groups(data: JsonNode, dimensions: Int): List<Group> = parse {
        val labels = data["seriesLabels"]; val totals = data["seriesCollapsed"]
        require(labels != null && labels.isArray && totals != null && totals.isArray && labels.size() == totals.size())
        labels.toList().mapIndexed { index, label ->
            val parts = if (label.isArray) label.toList().let { if (it.size == dimensions + 1 && it.first().isNumber) it.drop(1) else it } else listOf(label)
            require(parts.size == dimensions && parts.all { it.isString || it.isNull })
            val value = totals[index].let { if (it.isArray && it.size() == 1) it[0] else it }.let { if (it.isObject) it["value"] else it }
            Group(parts.map { if (it.isNull || it.asText() == "(none)") null else it.asText() }, number(requireNotNull(value)))
        }
    }
    fun <T> parse(block: () -> T): T = try { block() } catch (failure: AmplitudeFetchFailure) { throw failure } catch (failure: Exception) { throw AmplitudeFetchFailure(AnalyticsFailureReason.parse_error, exceptionClass = failure.javaClass.name, phase = "result_decode") }
}
