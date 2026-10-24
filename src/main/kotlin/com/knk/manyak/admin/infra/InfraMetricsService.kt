package com.knk.manyak.admin.infra

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@Service
class InfraMetricsService(private val gateway: InfraAwsGateway, private val catalog: InfraMetricCatalog,
    private val properties: InfraMetricsProperties, private val clock: Clock = Clock.systemUTC()) {
    private class State { var value: InfraMetricsResponse? = null; var retry: Instant = Instant.MIN; var busy = false }
    private val states = LinkedHashMap<Pair<LocalDate, LocalDate>, State>(16, 0.75f, true)
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "admin-infra-fetch").apply { isDaemon = true } }
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
        val stage = AtomicReference("cloudwatch")
        var pending: Future<InfraAwsValues>? = null
        try {
            if (catalog.queries(range.periodSeconds).isEmpty()) throw InfraFetchFailure("not_configured", null)
            val deadline = System.nanoTime() + Duration.ofSeconds(properties.timeoutSeconds).toNanos()
            pending = executor.submit<InfraAwsValues> { gateway.fetch(range, deadline, stage) }
            val values = pending.get(remaining(deadline, stage.get()).toNanos(), TimeUnit.NANOSECONDS)
            val result = assemble(range, values)
            synchronized(states) { state.value = result; state.retry = Instant.MIN }
            return result
        } catch (failure: Exception) {
            val cause = if (failure is ExecutionException) failure.cause else failure
            val safe = when (cause) {
                is InfraFetchFailure -> cause
                is TimeoutException -> InfraFetchFailure("timeout", stage.get())
                else -> InfraFetchFailure("aws_error", stage.get())
            }
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            synchronized(states) { state.retry = clock.instant().plusSeconds(properties.cooldownSeconds) }
            log.warn("admin_infra_metrics_fetch_failed reason={} service={} error_code={}", safe.reason, safe.service, safe.errorCode)
            return synchronized(states) { state.value } ?: throw InfraMetricsUnavailable()
        } finally {
            pending?.takeUnless { it.isDone }?.cancel(true)
            synchronized(states) { state.busy = false }
        }
    }
    private fun assemble(range: InfraRange, data: InfraAwsValues): InfraMetricsResponse {
        fun points(id: String, factor: Double = 1.0): List<InfraMetricPoint> = data.series.getValue(id).map {
            InfraMetricPoint(it.at, (it.value * factor).also { value -> check(value.isFinite()) }) }
        fun counts(id: String): List<InfraMetricPoint> {
            val values = points(id).associate { it.at to it.value }
            return generateSequence(range.since) { it.plusSeconds(range.periodSeconds.toLong()) }.takeWhile { it < range.until }
                .map { InfraMetricPoint(it, values[it] ?: 0.0) }.toList()
        }
        val pairs = properties.servicePairs()
        return InfraMetricsResponse(clock.instant(), range.from, range.to, range.periodSeconds,
            if (pairs.isEmpty()) null else InfraEcsMetrics(pairs.mapIndexed { i, (key, name) ->
                val task = data.tasks.getValue(name)
                InfraServiceMetrics(key, task.running, task.desired, task.pending, points("ecs_${i}_cpu"), points("ecs_${i}_memory"),
                    points("ecs_${i}_cpu_max"), points("ecs_${i}_memory_max")) }),
            if (properties.loadBalancer.isBlank()) null else InfraAlbMetrics(counts("alb_requests"), counts("alb_elb_5xx"), counts("alb_target_5xx"), points("alb_response_p95", 1000.0)),
            if (properties.database.isBlank()) null else InfraRdsMetrics(points("rds_cpu"), points("rds_storage"), points("rds_connections"), points("rds_read_latency", 1000.0), points("rds_write_latency", 1000.0)),
            if (properties.nodes().isEmpty()) null else InfraRedisMetrics(properties.nodes().mapIndexed { i, name -> InfraRedisNode(name, points("cache_${i}_cpu"), points("cache_${i}_memory"), points("cache_${i}_connections")) }),
            if (!properties.hasSearch()) null else InfraSearchMetrics(points("search_storage", 1048576.0), points("search_jvm")))
    }
}
