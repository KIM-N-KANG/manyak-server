package com.knk.manyak.admin.infra

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.time.Instant
import java.util.Base64
import java.util.concurrent.*

data class InfraPrometheusValues(val hostSystem: InfraHostSystemMetrics, val requests: InfraRequestMetrics, val opensearch: InfraSearchMetrics)

/**
 * 관측 서버 Prometheus에서 기록 규칙 계열만 읽는다(ADMIN 4-1-21). 질의는 고정이고 요청 값은 기간과 간격뿐이다.
 * 범위 질의의 점 하나는 그 시각 직전 periodSeconds 동안의 값이라 점의 시각에서 periodSeconds를 빼 구간 시작으로 바꾼다.
 * 질의 하나라도 HTTP 오류, status가 success가 아님, 시간 초과면 이 출처 전체의 실패다.
 */
@Component
class PrometheusInfraClient(private val properties: InfraMetricsProperties, private val mapper: ObjectMapper) {
    private val http = HttpClient.newBuilder().connectTimeout(properties.prometheusConnectTimeout).followRedirects(HttpClient.Redirect.NEVER).build()

    fun fetch(range: InfraRange, now: Instant, deadline: Long): InfraPrometheusValues {
        val p = range.periodSeconds
        // 끝은 조회 끝을 덮는 첫 간격 경계다. 지금보다 뒤인 점도 그 시각까지 쌓인 값만 담는다.
        val steps = (range.until.epochSecond - range.since.epochSecond + p - 1) / p
        val start = range.since.epochSecond + p; val end = range.since.epochSecond + maxOf(1, steps) * p
        val ranges = mapOf(
            "requests" to "sum(sum_over_time(manyak_server:http_requests:rate3m[${p}s])) * 60",
            "server5xx" to "sum(sum_over_time(manyak_server:http_5xx:rate3m[${p}s])) * 60",
            "p95" to "histogram_quantile(0.95, sum by (le) (sum_over_time(manyak_server:http_latency_bucket_nostream:rate3m[${p}s])))",
            "memoryAvailable" to "min(min_over_time(manyak_prod_host:memory_available_bytes[${p}s]))",
            "diskAvailable" to "min by (mountpoint) (min_over_time(manyak_prod_host:disk_available_bytes[${p}s]))",
            "searchDiskAvailable" to "min(min_over_time(manyak_opensearch:disk_available_bytes[${p}s]))",
            "jvm" to "max(max_over_time(manyak_opensearch:jvm_memory_pressure_percent[${p}s]))",
        ).mapValues { (_, query) -> "/api/v1/query_range?query=${encode(query)}&start=$start&end=$end&step=$p" }
        val instants = mapOf(
            "memoryTotal" to "max(manyak_prod_host:memory_total_bytes)",
            "diskTotal" to "max by (mountpoint) (manyak_prod_host:disk_total_bytes)",
            "searchDiskTotal" to "max(manyak_opensearch:disk_total_bytes)",
            "status" to "max(manyak_opensearch:cluster_status)",
        ).mapValues { (_, query) -> "/api/v1/query?query=${encode(query)}&time=${now.epochSecond}" }
        val pending = (ranges + instants).mapValues { (_, path) -> http.sendAsync(request(path), HttpResponse.BodyHandlers.ofString()) }
        try {
            val queryDeadline = System.nanoTime() + properties.prometheusQueryTimeout.toNanos()
            val results = pending.mapValues { (_, future) -> result(future, minOf(queryDeadline, deadline)) }
            fun series(key: String) = results.getValue(key).map { it["metric"]["mountpoint"]?.asText() to it }
            fun points(key: String, round: Boolean = false) = series(key).firstOrNull()?.second?.let { toPoints(it, p, round) } ?: emptyList()
            fun last(key: String) = series(key).firstOrNull()?.second?.let(::value)
            val totals = series("diskTotal").associate { (mount, node) -> mount to value(node)?.toLong() }
            val available = series("diskAvailable").associate { (mount, node) -> mount to toPoints(node, p, false) }
            val disks = (totals.keys + available.keys).filterNotNull().sorted().map { InfraDisk(it, totals[it], available[it] ?: emptyList()) }
            return InfraPrometheusValues(
                InfraHostSystemMetrics(last("memoryTotal")?.toLong(), points("memoryAvailable"), disks),
                InfraRequestMetrics(points("requests", true), points("server5xx", true), points("p95")),
                InfraSearchMetrics(last("status")?.let { STATUS[it.toInt()] }, last("searchDiskTotal")?.toLong(), points("searchDiskAvailable"), points("jvm")))
        } finally { pending.values.forEach { if (!it.isDone) it.cancel(true) } }
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun request(path: String) = HttpRequest.newBuilder(URI.create(properties.prometheusUrl.trimEnd('/') + path))
        .timeout(properties.prometheusQueryTimeout).header("Accept", "application/json").GET().apply {
            if (properties.prometheusUsername.isNotBlank()) {
                val basic = Base64.getEncoder().encodeToString("${properties.prometheusUsername}:${properties.prometheusPassword}".toByteArray())
                header("Authorization", "Basic $basic")
            }
            if (properties.prometheusAccessClientId.isNotBlank()) {
                header("CF-Access-Client-Id", properties.prometheusAccessClientId)
                header("CF-Access-Client-Secret", properties.prometheusAccessClientSecret)
            }
        }.build()

    private fun result(future: CompletableFuture<HttpResponse<String>>, deadline: Long): List<JsonNode> {
        // 앞 질의를 기다리는 동안 끝난 질의는 한도가 지났어도 결과를 받는다(get(0)은 완료된 값을 바로 돌려줌).
        val response = try { future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
        catch (_: TimeoutException) { throw InfraFetchFailure("timeout", SERVICE) }
        catch (failure: ExecutionException) { throw InfraFetchFailure(if (failure.cause is HttpTimeoutException) "timeout" else "prometheus_error", SERVICE) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw InfraFetchFailure("timeout", SERVICE) }
        val status = response.statusCode()
        if (status == 401 || status == 403) throw InfraFetchFailure("access_denied", SERVICE, status.toString())
        if (status !in 200..299) throw InfraFetchFailure("prometheus_error", SERVICE, status.toString())
        val body = try { mapper.readTree(response.body()) } catch (_: Exception) { throw InfraFetchFailure("prometheus_error", SERVICE) }
        if (body["status"]?.asText() != "success") {
            throw InfraFetchFailure("prometheus_error", SERVICE, body["errorType"]?.asText()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) })
        }
        return body["data"]?.get("result")?.toList() ?: throw InfraFetchFailure("prometheus_error", SERVICE)
    }

    private fun value(node: JsonNode) = node["value"]?.get(1)?.asText()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** 값이 없는 구간과 NaN(요청 없는 p95)은 점을 만들지 않는다. 건수는 정수로 반올림한다. */
    private fun toPoints(node: JsonNode, period: Int, round: Boolean) = node["values"].mapNotNull { pair ->
        val value = pair[1].asText().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        InfraMetricPoint(Instant.ofEpochSecond(pair[0].asDouble().toLong() - period), if (round) Math.round(value).toDouble() else value)
    }

    private companion object {
        const val SERVICE = "prometheus"
        val STATUS = mapOf(0 to "green", 1 to "yellow", 2 to "red")
    }
}
