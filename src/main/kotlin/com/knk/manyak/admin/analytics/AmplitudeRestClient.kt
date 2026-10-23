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
    fun fetch(def: AnalyticsDefinition, range: AnalyticsRange, deadline: Long, onSend: () -> Unit = {}): JsonNode {
        if (properties.apiKey.isBlank() || properties.secretKey.isBlank()) throw AmplitudeFetchFailure(AnalyticsFailureReason.no_credentials)
        budget.enter(deadline)
        var pending: java.util.concurrent.CompletableFuture<HttpResponse<String>>? = null
        var status: Int? = null
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
            if (status !in 200..299) throw AmplitudeFetchFailure(if (status == 429) AnalyticsFailureReason.rate_limited else AnalyticsFailureReason.http_error, status)
            return requireNotNull(mapper.readTree(response.body())["data"])
        } catch (failure: AmplitudeFetchFailure) { throw failure }
        catch (_: java.util.concurrent.TimeoutException) { throw AmplitudeFetchFailure(AnalyticsFailureReason.timeout) }
        catch (failure: java.util.concurrent.ExecutionException) { throw AmplitudeFetchFailure(if (failure.cause is HttpTimeoutException) AnalyticsFailureReason.timeout else AnalyticsFailureReason.http_error, status) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error, status) }
        catch (_: Exception) { throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error, status) }
        finally { pending?.takeUnless { it.isDone }?.cancel(true); budget.leave() }
    }
}

/** Amplitude 문서의 일별 series·그룹별 seriesCollapsed·퍼널 cumulativeRaw를 검증한다. */
object AmplitudeResults {
    fun number(node: JsonNode): Long { require(node.isIntegralNumber && node.canConvertToLong() && node.asLong() >= 0); return node.asLong() }
    fun series(data: JsonNode, range: AnalyticsRange): List<Long> = parse {
        val dates = data["xValues"]; val values = data["series"]
        require(dates != null && dates.isArray && dates.size() == range.days && values != null && values.isArray && values.size() == 1)
        require(values[0].isArray && values[0].size() == range.days)
        val keyed = linkedMapOf<LocalDate, Long>()
        dates.forEachIndexed { index, date -> require(date.isString); require(keyed.put(LocalDate.parse(date.asText()), number(values[0][index])) == null) }
        range.dates().map { requireNotNull(keyed[it]) }
    }
    fun funnel(data: JsonNode, steps: Int): List<Long> = parse {
        require(data.isArray && data.size() == 1)
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
    fun <T> parse(block: () -> T): T = try { block() } catch (failure: AmplitudeFetchFailure) { throw failure } catch (_: Exception) { throw AmplitudeFetchFailure(AnalyticsFailureReason.http_error) }
}
