package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AnalyticsRange(val from: LocalDate, val to: LocalDate) {
    val days: Int get() = Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1)
    fun dates() = (0 until days).map { from.plusDays(it.toLong()) }
}
class DailyAnalyticsMetric(val definition: AnalyticsDefinition, val retention: Boolean = false,
    val decode: (JsonNode, AnalyticsRange) -> Map<LocalDate, JsonNode>)
class RangeAnalyticsMetric(val definition: AnalyticsDefinition, val range: AnalyticsRange, val decode: (JsonNode) -> JsonNode)
class AnalyticsSnapshotResult(
    val daily: Map<String, Map<LocalDate, AnalyticsValue>>, val ranges: Map<String, AnalyticsValue>, val metrics: List<DailyAnalyticsMetric>, val range: AnalyticsRange,
) {
    fun values(date: LocalDate) = metrics.mapNotNull { daily[it.definition.hash]?.get(date) }
    fun readyDates() = range.dates().filter { values(it).size == metrics.size }
    fun pendingDates() = range.dates() - readyDates().toSet()
    fun value(metric: DailyAnalyticsMetric, date: LocalDate) = daily[metric.definition.hash]?.get(date)
    fun value(metric: RangeAnalyticsMetric) = ranges[metric.definition.hash + ":" + metric.range.from + ":" + metric.range.to]
    fun asOf(values: Collection<AnalyticsValue>): Instant {
        val mutable = values.filter { it.finalizedAt == null }
        return if (mutable.isNotEmpty()) mutable.minOf { it.fetchedAt } else values.maxOf { it.fetchedAt }
    }
}

/** DB에는 조회별 원값만 남긴다. 메모리에는 단일 갱신 표식과 실패 쿨다운만 둔다. */
@Service
class AnalyticsSnapshots(private val store: AnalyticsValueRepository, private val client: AmplitudeRestClient, private val budget: AmplitudeBudget,
    private val mapper: ObjectMapper, private val clock: Clock = Clock.systemUTC(), private val policy: AnalyticsPolicy = AnalyticsPolicy()) {
    private class State { val refreshing = AtomicBoolean(); @Volatile var retry = Instant.MIN }
    private val states = linkedMapOf<String, State>()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    private fun today() = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
    private fun rangeKey(metric: RangeAnalyticsMetric) = metric.definition.hash + ":" + metric.range.from + ":" + metric.range.to
    private fun completeRetention(value: JsonNode) = value.isArray && value.size() >= 31 && (0..30).all { value[it]["incomplete"]?.isBoolean == true && !value[it]["incomplete"].asBoolean() }
    private fun finalDaily(metric: DailyAnalyticsMetric, date: LocalDate, value: AnalyticsValue): Boolean = value.finalizedAt != null ||
        if (metric.retention) completeRetention(value.value) else date <= today().minusDays(2) && value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate() > date
    private fun freshDaily(metric: DailyAnalyticsMetric, date: LocalDate, value: AnalyticsValue?): Boolean {
        value ?: return false
        if (finalDaily(metric, date, value)) return true
        val fetched = value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate()
        return if (metric.retention || date == today().minusDays(1)) fetched == today()
        else date == today() && fetched == today() && clock.instant().isBefore(value.fetchedAt.plusSeconds(policy.currentTtlSeconds))
    }
    private fun finalRange(metric: RangeAnalyticsMetric, value: AnalyticsValue) = value.finalizedAt != null ||
        metric.range.to <= today().minusDays(2) && value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate() > metric.range.to
    private fun freshRange(metric: RangeAnalyticsMetric, value: AnalyticsValue?) = value != null &&
        (finalRange(metric, value) || clock.instant().isBefore(value.fetchedAt.plusSeconds(policy.currentTtlSeconds)))

    fun load(endpoint: String, range: AnalyticsRange, metrics: List<DailyAnalyticsMetric>, extraDaily: List<Pair<DailyAnalyticsMetric, LocalDate>> = emptyList(),
        rangeMetrics: List<RangeAnalyticsMetric> = emptyList(), timeout: Duration = Duration.ofSeconds(policy.refreshTimeoutSeconds)): AnalyticsSnapshotResult {
        val dailySpecs = (metrics + extraDaily.map { it.first }).distinctBy { it.definition.hash }
        val daily = dailySpecs.associate { metric ->
            val dates = range.dates() + extraDaily.filter { it.first.definition.hash == metric.definition.hash }.map { it.second }
            metric.definition.hash to store.daily(metric.definition, dates.min(), dates.max()).toMutableMap()
        }.toMutableMap()
        val ranges = rangeMetrics.mapNotNull { metric -> store.range(metric.definition, metric.range.from, metric.range.to)?.let { rangeKey(metric) to it } }.toMap().toMutableMap()
        var finalized = 0
        fun storeFailure(metric: String, failure: Exception) = log.warn("admin_analytics_store_failed endpoint={} metric={} exception={}", endpoint, metric, failure.javaClass.simpleName)
        fun finalizeReadValues() {
            dailySpecs.forEach { metric -> daily[metric.definition.hash]!!.toMap().forEach { (date, value) ->
                if (value.finalizedAt == null && finalDaily(metric, date, value)) {
                    try { store.finalizeDaily(metric.definition, date, value, clock.instant()); finalized++ } catch (failure: Exception) { storeFailure(metric.definition.metric, failure) }
                    daily[metric.definition.hash]!![date] = value.copy(finalizedAt = clock.instant())
                }
            } }
            rangeMetrics.forEach { metric -> ranges[rangeKey(metric)]?.let { value -> if (value.finalizedAt == null && finalRange(metric, value)) {
                try { store.finalizeRange(metric.definition, metric.range.from, metric.range.to, value, clock.instant()); finalized++ } catch (failure: Exception) { storeFailure(metric.definition.metric, failure) }
                ranges[rangeKey(metric)] = value.copy(finalizedAt = clock.instant())
            } } }
        }
        finalizeReadValues()
        fun result() = AnalyticsSnapshotResult(daily, ranges, metrics, range)
        fun missing(metric: DailyAnalyticsMetric, date: LocalDate) = !freshDaily(metric, date, daily[metric.definition.hash]?.get(date))
        val needs = metrics.any { metric -> range.dates().any { missing(metric, it) } } || extraDaily.any { missing(it.first, it.second) } || rangeMetrics.any { !freshRange(it, ranges[rangeKey(it)]) }
        if (!needs) return result()
        val key = endpoint + ":" + range.from + ":" + range.to + ":" + dailySpecs.joinToString { it.definition.hash } + ":" + rangeMetrics.joinToString { rangeKey(it) }
        val state = synchronized(states) {
            states.getOrPut(key) {
                if (states.size >= 256) states.entries.firstOrNull { !it.value.refreshing.get() }?.key?.let { states.remove(it) }
                State()
            }
        }
        if (clock.instant().isBefore(state.retry) || !state.refreshing.compareAndSet(false, true)) return result()
        val started = System.nanoTime(); val deadline = started + timeout.toNanos()
        var sent = 0; var cost = 0; var fetchedRange = false; val fetchedDates = mutableSetOf<LocalDate>()
        try {
            if (clock.instant().isBefore(state.retry)) return result()
            data class DailyPlan(val metric: DailyAnalyticsMetric, val dates: List<LocalDate>)
            data class Plan(val daily: List<DailyPlan>, val ranges: List<RangeAnalyticsMetric>)
            val (reservation, plan) = budget.plan { available ->
                var remaining = available
                val neededRanges = rangeMetrics.filter { !freshRange(it, ranges[rangeKey(it)]) }
                val rangeCost = neededRanges.sumOf { it.range.days * it.definition.perDay }
                val selectedRanges = if (metrics.isEmpty()) {
                    if (rangeCost <= remaining) { remaining -= rangeCost; neededRanges } else emptyList()
                } else neededRanges.filter { metric ->
                    val expected = metric.range.days * metric.definition.perDay
                    if (expected <= remaining) { remaining -= expected; true } else false
                }
                val dates = linkedMapOf<DailyAnalyticsMetric, MutableSet<LocalDate>>()
                extraDaily.sortedByDescending { it.second }.forEach { (metric, date) ->
                    if (missing(metric, date) && metric.definition.perDay <= remaining) {
                        dates.getOrPut(metric) { linkedSetOf() }.add(date); remaining -= metric.definition.perDay
                    }
                }
                range.dates().asReversed().forEach { date ->
                    val needed = metrics.filter { missing(it, date) && date !in (dates[it] ?: emptySet()) }
                    val expected = needed.sumOf { it.definition.perDay }
                    if (expected <= remaining) { needed.forEach { dates.getOrPut(it) { linkedSetOf() }.add(date) }; remaining -= expected }
                }
                available - remaining to Plan(dates.map { DailyPlan(it.key, it.value.sortedDescending()) }, selectedRanges)
            }
            reservation.use {
                if (plan.daily.isEmpty() && plan.ranges.isEmpty()) throw AmplitudeFetchFailure(AnalyticsFailureReason.budget_exceeded)
                for (metric in plan.ranges) {
                    val current = store.range(metric.definition, metric.range.from, metric.range.to)
                    if (freshRange(metric, current)) { ranges[rangeKey(metric)] = current!!; continue }
                    val value = AmplitudeResults.parse { metric.decode(client.fetch(metric.definition, metric.range, deadline) { sent++ }) }
                    val raw = AnalyticsValue(value, clock.instant()).let { if (finalRange(metric, it)) it.copy(finalizedAt = clock.instant()) else it }
                    val spent = metric.range.days * metric.definition.perDay; reservation.succeeded(spent); cost += spent; fetchedRange = true
                    try { store.saveRange(metric.definition, metric.range.from, metric.range.to, raw); ranges[rangeKey(metric)] = store.range(metric.definition, metric.range.from, metric.range.to) ?: raw; if (raw.finalizedAt != null) finalized++ }
                    catch (failure: Exception) { storeFailure(metric.definition.metric, failure); ranges[rangeKey(metric)] = raw }
                }
                for ((metric, selected) in plan.daily) {
                    val current = store.daily(metric.definition, selected.min(), selected.max())
                    daily[metric.definition.hash]!!.putAll(current)
                    val stillMissing = selected.filter { !freshDaily(metric, it, current[it]) }
                    val groups = mutableListOf<MutableList<LocalDate>>()
                    stillMissing.forEach { date ->
                        if (groups.isEmpty() || groups.last().last().minusDays(1) != date) groups.add(mutableListOf(date)) else groups.last().add(date)
                    }
                    for (group in groups) {
                        // 직전 구간을 받는 사이 다른 태스크가 저장했을 수 있다. 구간을 다시 분할한다.
                        val latest = store.daily(metric.definition, group.min(), group.max())
                        daily[metric.definition.hash]!!.putAll(latest)
                        val gaps = group.filter { !freshDaily(metric, it, latest[it]) }
                        val contiguous = mutableListOf<MutableList<LocalDate>>()
                        gaps.forEach { date -> if (contiguous.isEmpty() || contiguous.last().last().minusDays(1) != date) contiguous.add(mutableListOf(date)) else contiguous.last().add(date) }
                        for (gap in contiguous) {
                            val interval = AnalyticsRange(gap.min(), gap.max())
                            val values = AmplitudeResults.parse { metric.decode(client.fetch(metric.definition, interval, deadline) { sent++ }, interval).also { require(it.keys == interval.dates().toSet()) } }
                            val rows = values.mapValues { (date, value) -> AnalyticsValue(value, clock.instant()).let { if (finalDaily(metric, date, it)) it.copy(finalizedAt = clock.instant()) else it } }
                            val spent = interval.days * metric.definition.perDay; reservation.succeeded(spent); cost += spent; fetchedDates += interval.dates()
                            try { store.saveDaily(metric.definition, rows); daily[metric.definition.hash]!!.putAll(store.daily(metric.definition, interval.from, interval.to)); finalized += rows.values.count { it.finalizedAt != null } }
                            catch (failure: Exception) { storeFailure(metric.definition.metric, failure); daily[metric.definition.hash]!!.putAll(rows) }
                        }
                    }
                }
            }
            state.retry = Instant.MIN
        } catch (failure: AmplitudeFetchFailure) {
            state.retry = clock.instant().plusSeconds(policy.cooldownSeconds)
            log.warn("admin_analytics_fetch_failed reason={} status={} endpoint={}", failure.reason.name, failure.status, endpoint)
        } finally {
            finalizeReadValues()
            state.refreshing.set(false)
            if (sent > 0) log.info("admin_analytics_refreshed endpoint={} queries={} fetched_days={} reused_days={} pending_days={} cost={} finalized_rows={} duration_ms={}",
                endpoint, sent, if (metrics.isEmpty()) (if (fetchedRange) 1 else 0) else fetchedDates.size, if (metrics.isEmpty()) (if (!fetchedRange && rangeMetrics.all { ranges[rangeKey(it)] != null }) 1 else 0) else result().readyDates().count { it !in fetchedDates }, if (metrics.isEmpty()) (if (rangeMetrics.all { ranges[rangeKey(it)] != null }) 0 else 1) else result().pendingDates().size, cost, finalized, (System.nanoTime() - started) / 1_000_000)
        }
        return result()
    }
}
