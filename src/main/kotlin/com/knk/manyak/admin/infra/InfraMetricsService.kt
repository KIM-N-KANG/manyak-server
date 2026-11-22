package com.knk.manyak.admin.infra

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.time.*
import java.util.concurrent.*

/**
 * 인프라 지표(ADMIN 4-1-21). CloudWatch, 관측 서버 Prometheus, Redis를 나란히 부르고 합쳐 5초 한도를 둔다.
 * 실패는 출처마다 따로 판정해 그 출처의 묶음만 비운다. 부른 출처가 모두 실패하면 만료된 캐시나 사용 불가이며 60초 쿨다운을 건다.
 */
@Service
class InfraMetricsService(private val cloudwatch: CloudWatchInfraClient, private val prometheus: PrometheusInfraClient,
    private val redis: RedisInfraClient, private val catalog: InfraMetricCatalog,
    private val properties: InfraMetricsProperties, private val clock: Clock = Clock.systemUTC()) {
    private class State { var value: InfraMetricsResponse? = null; var retry: Instant = Instant.MIN; var busy = false }
    private class Fetched<T>(val value: T?, val failure: InfraFetchFailure?)
    private val states = LinkedHashMap<Pair<LocalDate, LocalDate>, State>(16, 0.75f, true)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    @PreDestroy fun close() { executor.shutdownNow() }

    fun metrics(query: InfraMetricsQuery): InfraMetricsResponse {
        val range = query.resolve(clock, properties); val key = range.from to range.to
        val state = synchronized(states) {
            val current = states[key] ?: run {
                if (states.size >= properties.cacheKeys) {
                    val oldest = states.entries.firstOrNull { !it.value.busy }?.key ?: throw InfraMetricsUnavailable()
                    states.remove(oldest)
                }
                State().also { states[key] = it }
            }
            current.value?.takeIf { clock.instant().isBefore(it.asOf.plusSeconds(properties.cacheSeconds)) }?.let { return it }
            if (current.busy || clock.instant().isBefore(current.retry)) return current.value ?: throw InfraMetricsUnavailable()
            current.busy = true; current
        }
        try {
            val result = fetch(range)
            if (result == null) {
                synchronized(states) { state.retry = clock.instant().plusSeconds(properties.cooldownSeconds) }
                return synchronized(states) { state.value } ?: throw InfraMetricsUnavailable()
            }
            synchronized(states) { state.value = result; state.retry = Instant.MIN }
            return result
        } finally {
            synchronized(states) { state.busy = false }
        }
    }

    /** 부른 출처가 모두 실패하면 null이다. 설정이 비어 부르지 않은 출처는 실패로 세지 않는다. */
    private fun fetch(range: InfraRange): InfraMetricsResponse? {
        val deadline = System.nanoTime() + Duration.ofSeconds(properties.timeoutSeconds).toNanos()
        val now = clock.instant()
        val queries = catalog.queries(range.periodSeconds)
        val cw = queries.takeIf { it.isNotEmpty() }?.let { submit { cloudwatch.fetch(it, range.since, range.until, deadline) } }
        val pm = properties.prometheusUrl.takeIf { it.isNotBlank() }?.let { submit { prometheus.fetch(range, now, deadline) } }
        val rd = submit { redis.fetch() }
        val redisDeadline = minOf(deadline, System.nanoTime() + properties.redisTimeout.toNanos())
        val series = cw?.let { await(it, "cloudwatch", deadline) }
        val values = pm?.let { await(it, "prometheus", deadline) }
        val current = await(rd, "redis", redisDeadline)
        val called = listOfNotNull(series, values, current)
        called.mapNotNull { it.failure }.forEach {
            log.warn("admin_infra_metrics_fetch_failed reason={} service={} error_code={}", it.reason, it.service, it.errorCode)
        }
        if (called.all { it.failure != null }) return null
        fun points(id: String, factor: Double = 1.0) = series!!.value!!.getValue(id).map { InfraMetricPoint(it.at, it.value * factor) }
        val cloud = series?.value != null
        val host = if (cloud && properties.hostAsg.isNotBlank()) InfraHostMetrics(points("host_cpu"), points("host_cpu_max"),
            points("host_cpu_credit"), points("host_status")) else null
        val rds = if (cloud && properties.database.isNotBlank()) InfraRdsMetrics(points("rds_cpu"), points("rds_storage"),
            points("rds_connections"), points("rds_read_latency", 1000.0), points("rds_write_latency", 1000.0)) else null
        val unavailable = buildList {
            if (series?.failure != null && properties.hostAsg.isNotBlank()) add("host")
            if (values?.failure != null) addAll(listOf("hostSystem", "requests"))
            if (series?.failure != null && properties.database.isNotBlank()) add("rds")
            if (current.failure != null) add("redis")
            if (values?.failure != null) add("opensearch")
        }
        return InfraMetricsResponse(clock.instant(), range.from, range.to, range.periodSeconds, unavailable,
            host, values?.value?.hostSystem, values?.value?.requests, rds, current.value, values?.value?.opensearch)
    }

    private fun <T> submit(action: () -> T): Future<T> = executor.submit<T> { action() }

    /** 앞 출처를 기다리는 동안 끝난 출처는 한도가 지났어도 결과를 받는다(get(0)은 완료된 값을 바로 돌려줌). */
    private fun <T> await(future: Future<T>, service: String, deadline: Long): Fetched<T> = try {
        Fetched(future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS), null)
    } catch (failure: Exception) {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        future.cancel(true)
        val cause = if (failure is ExecutionException) failure.cause else failure
        Fetched(null, when {
            cause is InfraFetchFailure -> cause
            cause is TimeoutException || cause is InterruptedException -> InfraFetchFailure("timeout", service)
            service == "redis" -> InfraFetchFailure("redis_error", service)
            service == "prometheus" -> InfraFetchFailure("prometheus_error", service)
            else -> InfraFetchFailure("aws_error", service)
        })
    }
}
