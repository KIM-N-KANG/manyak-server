package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.temporal.ChronoUnit
import jakarta.annotation.PreDestroy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

data class AnalyticsRange(val from: LocalDate, val to: LocalDate) {
    val days: Int get() = Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1)
    fun dates() = (0 until days).map { from.plusDays(it.toLong()) }
}
/** [perDate]면 응답이 범위 전체의 합이라 연속 날짜를 한 구간으로 묶지 않고 날짜마다 조회한다. */
class DailyAnalyticsMetric(val definition: AnalyticsDefinition, val retention: Boolean = false, val perDate: Boolean = false,
    val decode: (JsonNode, AnalyticsRange) -> Map<LocalDate, JsonNode>)
class RangeAnalyticsMetric(val definition: AnalyticsDefinition, val range: AnalyticsRange, val conversionDays: Long = 0, val decode: (JsonNode) -> JsonNode)
class AnalyticsRefreshProgress {
    var cost: Int = 0; internal set
    var fetchedDays: Int = 0; internal set
    var failure: AnalyticsFailureReason? = null; internal set
}
class AnalyticsSnapshotResult(
    val daily: Map<String, Map<LocalDate, AnalyticsValue>>, val ranges: Map<String, AnalyticsValue>, val metrics: List<DailyAnalyticsMetric>, val range: AnalyticsRange,
    val progress: AnalyticsRefreshProgress = AnalyticsRefreshProgress(),
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

/** 태스크 전용 갱신 실행기. Amplitude 호출이 태스크 안에서 한 번에 하나라 스레드는 2개로 고정하고 대기열 상한은 두지 않는다. */
private fun refreshExecutor(): ExecutorService = ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
    Thread(task, "admin-analytics-refresh").apply { isDaemon = true }
}.apply { allowCoreThreadTimeOut(true) }

/** DB에는 조회별 원값만 남긴다. 메모리에는 단일 갱신 표식과 실패 쿨다운만 둔다. */
@Service
class AnalyticsSnapshots(private val store: AnalyticsValueRepository, private val client: AmplitudeRestClient, private val budget: AmplitudeBudget,
    private val mapper: ObjectMapper, private val clock: Clock = Clock.systemUTC(), private val policy: AnalyticsPolicy = AnalyticsPolicy(),
    private val executor: ExecutorService = refreshExecutor()) {
    private class State { val refreshing = AtomicBoolean(); @Volatile var retry = Instant.MIN }
    private val states = linkedMapOf<String, State>()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    private fun today() = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
    private fun rangeKey(metric: RangeAnalyticsMetric) = metric.definition.hash + ":" + metric.range.from + ":" + metric.range.to
    private fun completeRetention(value: JsonNode) = value.isArray && value.size() >= 32 && (0..31).all { value[it]["incomplete"]?.isBoolean == true && !value[it]["incomplete"].asBoolean() }
    private fun finalDaily(metric: DailyAnalyticsMetric, date: LocalDate, value: AnalyticsValue): Boolean =
        if (metric.retention) completeRetention(value.value) else value.finalizedAt != null || date <= today().minusDays(2) && value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate() > date
    private fun dailyValues(metric: DailyAnalyticsMetric, from: LocalDate, to: LocalDate): Map<LocalDate, AnalyticsValue> =
        store.daily(metric.definition, from, to).mapValues { (date, value) ->
            if (metric.retention && value.finalizedAt != null && !completeRetention(value.value)) {
                // 이전 태스크의 옛 판정도 읽을 때 풀어 기존 upsert가 새 응답을 저장할 수 있게 한다.
                store.unfinalizeDaily(metric.definition, date, value)
                value.copy(finalizedAt = null)
            } else value
        }
    private fun freshDaily(metric: DailyAnalyticsMetric, date: LocalDate, value: AnalyticsValue?): Boolean {
        value ?: return false
        if (finalDaily(metric, date, value)) return true
        val fetched = value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate()
        return if (metric.retention || date == today().minusDays(1)) fetched == today()
        else date == today() && fetched == today() && clock.instant().isBefore(value.fetchedAt.plusSeconds(policy.currentTtlSeconds))
    }
    private fun finalRange(metric: RangeAnalyticsMetric, value: AnalyticsValue): Boolean {
        val fetched = value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate()
        return if (metric.conversionDays > 0) metric.range.to <= today().minusDays(2) && fetched > metric.range.to.plusDays(metric.conversionDays)
        else value.finalizedAt != null || metric.range.to <= today().minusDays(2) && fetched > metric.range.to
    }
    private fun rangeValue(metric: RangeAnalyticsMetric): AnalyticsValue? = store.range(metric.definition, metric.range.from, metric.range.to)?.let { value ->
        if (metric.conversionDays > 0 && value.finalizedAt != null && !finalRange(metric, value)) {
            store.unfinalizeRange(metric.definition, metric.range.from, metric.range.to, value)
            value.copy(finalizedAt = null)
        } else value
    }
    private fun freshRange(metric: RangeAnalyticsMetric, value: AnalyticsValue?): Boolean {
        value ?: return false
        if (finalRange(metric, value)) return true
        return if (metric.conversionDays > 0 && metric.range.to <= today().minusDays(2))
            value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate() == today()
        else clock.instant().isBefore(value.fetchedAt.plusSeconds(policy.currentTtlSeconds))
    }

    /** 배치도 화면과 같은 확정일·어제·리텐션 유효 판정을 쓴다. */
    fun missingDates(metric: DailyAnalyticsMetric, range: AnalyticsRange): List<LocalDate> {
        val values = dailyValues(metric, range.from, range.to)
        values.forEach { (date, value) ->
            if (value.finalizedAt == null && finalDaily(metric, date, value)) {
                try { store.finalizeDaily(metric.definition, date, value, clock.instant()) }
                catch (failure: Exception) { log.warn("admin_analytics_store_failed endpoint=batch metric={} exception={}", metric.definition.metric, failure.javaClass.simpleName) }
            }
        }
        return range.dates().filter { !freshDaily(metric, it, values[it]) }
    }

    /** 요청 스레드와 백그라운드 갱신이 같은 맵을 만지지 않도록 갱신에는 복사본을 넘긴다. */
    private class Values(val daily: MutableMap<String, MutableMap<LocalDate, AnalyticsValue>>, val ranges: MutableMap<String, AnalyticsValue>) {
        fun copy() = Values(daily.mapValues { it.value.toMutableMap() }.toMutableMap(), ranges.toMutableMap())
    }

    /**
     * 저장 값으로 응답하고 값이 없거나 만료된 날짜는 백그라운드 갱신을 시작한다.
     * 저장 값이 없는 날짜나 범위가 있을 때만 그 갱신을 [AnalyticsPolicy.requestWaitSeconds]까지 기다린다.
     * 배치와 백필([backgroundLimit]가 있음)은 백그라운드 갱신을 띄우지 않고 호출한 스레드에서 차례로 갱신한다.
     */
    fun load(endpoint: String, range: AnalyticsRange, metrics: List<DailyAnalyticsMetric>, extraDaily: List<Pair<DailyAnalyticsMetric, LocalDate>> = emptyList(),
        rangeMetrics: List<RangeAnalyticsMetric> = emptyList(), timeout: Duration = Duration.ofSeconds(policy.refreshTimeoutSeconds), backgroundLimit: Int? = null): AnalyticsSnapshotResult {
        val dailySpecs = (metrics + extraDaily.map { it.first }).distinctBy { it.definition.hash }
        fun storeFailure(metric: String, failure: Exception) = log.warn("admin_analytics_store_failed endpoint={} metric={} exception={}", endpoint, metric, failure.javaClass.simpleName)
        /** 읽은 값 가운데 이제 확정할 수 있는 값을 확정하고 확정한 행 수를 돌려준다. */
        fun finalizeReadValues(values: Values): Int {
            var finalized = 0
            dailySpecs.forEach { metric -> values.daily[metric.definition.hash]!!.toMap().forEach { (date, value) ->
                if (value.finalizedAt == null && finalDaily(metric, date, value)) {
                    try { store.finalizeDaily(metric.definition, date, value, clock.instant()); finalized++ } catch (failure: Exception) { storeFailure(metric.definition.metric, failure) }
                    values.daily[metric.definition.hash]!![date] = value.copy(finalizedAt = clock.instant())
                }
            } }
            rangeMetrics.forEach { metric -> values.ranges[rangeKey(metric)]?.let { value -> if (value.finalizedAt == null && finalRange(metric, value)) {
                try { store.finalizeRange(metric.definition, metric.range.from, metric.range.to, value, clock.instant()); finalized++ } catch (failure: Exception) { storeFailure(metric.definition.metric, failure) }
                values.ranges[rangeKey(metric)] = value.copy(finalizedAt = clock.instant())
            } } }
            return finalized
        }
        fun read() = Values(dailySpecs.associate { metric ->
            val dates = range.dates() + extraDaily.filter { it.first.definition.hash == metric.definition.hash }.map { it.second }
            metric.definition.hash to dailyValues(metric, dates.min(), dates.max()).toMutableMap()
        }.toMutableMap(), rangeMetrics.mapNotNull { metric -> rangeValue(metric)?.let { rangeKey(metric) to it } }.toMap().toMutableMap())
        fun result(values: Values, progress: AnalyticsRefreshProgress = AnalyticsRefreshProgress()) = AnalyticsSnapshotResult(values.daily, values.ranges, metrics, range, progress)
        fun missing(values: Values, metric: DailyAnalyticsMetric, date: LocalDate) = !freshDaily(metric, date, values.daily[metric.definition.hash]?.get(date))

        val stored = read()
        val readFinalized = finalizeReadValues(stored)
        val needs = metrics.any { metric -> range.dates().any { missing(stored, metric, it) } } || extraDaily.any { missing(stored, it.first, it.second) } ||
            rangeMetrics.any { !freshRange(it, stored.ranges[rangeKey(it)]) }
        if (!needs) return result(stored)
        val key = endpoint + ":" + range.from + ":" + range.to + ":" + dailySpecs.joinToString { it.definition.hash } + ":" + rangeMetrics.joinToString { rangeKey(it) }
        val state = synchronized(states) {
            states.getOrPut(key) {
                if (states.size >= 256) states.entries.firstOrNull { !it.value.refreshing.get() }?.key?.let { states.remove(it) }
                State()
            }
        }
        if (clock.instant().isBefore(state.retry) || !state.refreshing.compareAndSet(false, true)) return result(stored)
        // 시간 한도는 실행기에 넣은 시각부터 잰다. 실행기 차례와 호출 차례를 기다린 시간도 포함한다.
        val started = System.nanoTime(); val deadline = started + timeout.toNanos()

        fun refresh(values: Values): AnalyticsSnapshotResult {
            val progress = AnalyticsRefreshProgress()
            val daily = values.daily; val ranges = values.ranges
            fun result() = result(values, progress)
            fun missing(metric: DailyAnalyticsMetric, date: LocalDate) = missing(values, metric, date)
            var finalized = readFinalized
            var sent = 0; var cost = 0; var fetchedRange = false; var fetchingMetric: String? = null; val fetchedDates = mutableSetOf<LocalDate>()
            try {
                if (clock.instant().isBefore(state.retry)) return result()
                data class DailyPlan(val metric: DailyAnalyticsMetric, val dates: List<LocalDate>)
                data class Plan(val daily: List<DailyPlan>, val ranges: List<RangeAnalyticsMetric>)
                val (reservation, plan) = budget.plan(backgroundLimit) { available ->
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
                        val current = rangeValue(metric)
                        if (freshRange(metric, current)) { ranges[rangeKey(metric)] = current!!; continue }
                        fetchingMetric = metric.definition.metric
                        val value = client.fetchDecoded(metric.definition, metric.range, deadline, onSend = { sent++ }) { metric.decode(it) }
                        val raw = AnalyticsValue(value, clock.instant()).let { if (finalRange(metric, it)) it.copy(finalizedAt = clock.instant()) else it }
                        val spent = metric.range.days * metric.definition.perDay; reservation.succeeded(spent); cost += spent; fetchedRange = true
                        try { store.saveRange(metric.definition, metric.range.from, metric.range.to, raw); ranges[rangeKey(metric)] = rangeValue(metric) ?: raw; if (raw.finalizedAt != null) finalized++ }
                        catch (failure: Exception) { storeFailure(metric.definition.metric, failure); ranges[rangeKey(metric)] = raw }
                    }
                    for ((metric, selected) in plan.daily) {
                        val current = dailyValues(metric, selected.min(), selected.max())
                        daily[metric.definition.hash]!!.putAll(current)
                        val stillMissing = selected.filter { !freshDaily(metric, it, current[it]) }
                        val groups = mutableListOf<MutableList<LocalDate>>()
                        stillMissing.forEach { date ->
                            if (groups.isEmpty() || groups.last().last().minusDays(1) != date) groups.add(mutableListOf(date)) else groups.last().add(date)
                        }
                        for (group in groups) {
                            // 직전 구간을 받는 사이 다른 태스크가 저장했을 수 있다. 구간을 다시 분할한다.
                            val latest = dailyValues(metric, group.min(), group.max())
                            daily[metric.definition.hash]!!.putAll(latest)
                            val gaps = group.filter { !freshDaily(metric, it, latest[it]) }
                            val contiguous = mutableListOf<MutableList<LocalDate>>()
                            gaps.forEach { date -> if (contiguous.isEmpty() || metric.perDate || contiguous.last().last().minusDays(1) != date) contiguous.add(mutableListOf(date)) else contiguous.last().add(date) }
                            for (gap in contiguous) {
                                val interval = AnalyticsRange(gap.min(), gap.max())
                                fetchingMetric = metric.definition.metric
                                val fetched = client.fetchDecoded(metric.definition, interval, deadline, onSend = { sent++ }) { data ->
                                    metric.decode(data, interval).also { require(it.keys == interval.dates().toSet()) }
                                }
                                val rows = fetched.mapValues { (date, value) -> AnalyticsValue(value, clock.instant()).let { if (finalDaily(metric, date, it)) it.copy(finalizedAt = clock.instant()) else it } }
                                val spent = interval.days * metric.definition.perDay; reservation.succeeded(spent); cost += spent; fetchedDates += interval.dates()
                                try { store.saveDaily(metric.definition, rows); daily[metric.definition.hash]!!.putAll(dailyValues(metric, interval.from, interval.to)); finalized += rows.values.count { it.finalizedAt != null } }
                                catch (failure: Exception) { storeFailure(metric.definition.metric, failure); daily[metric.definition.hash]!!.putAll(rows) }
                            }
                        }
                    }
                }
                state.retry = Instant.MIN
            } catch (failure: AmplitudeFetchFailure) {
                progress.failure = failure.reason
                state.retry = clock.instant().plusSeconds(policy.cooldownSeconds)
                log.warn("admin_analytics_fetch_failed reason={} status={} endpoint={} metric={} exception={} error_code={} phase={}",
                    failure.reason.name, failure.status, endpoint, fetchingMetric, failure.exceptionClass ?: failure.javaClass.name, failure.errorCode, failure.phase)
            } finally {
                progress.cost = cost; progress.fetchedDays = fetchedDates.size
                finalized += finalizeReadValues(values)
                state.refreshing.set(false)
                if (sent > 0) log.info("admin_analytics_refreshed endpoint={} queries={} fetched_days={} reused_days={} pending_days={} cost={} finalized_rows={} duration_ms={}",
                    endpoint, sent, if (metrics.isEmpty()) (if (fetchedRange) 1 else 0) else fetchedDates.size, if (metrics.isEmpty()) (if (!fetchedRange && rangeMetrics.all { ranges[rangeKey(it)] != null }) 1 else 0) else result().readyDates().count { it !in fetchedDates }, if (metrics.isEmpty()) (if (rangeMetrics.all { ranges[rangeKey(it)] != null }) 0 else 1) else result().pendingDates().size, cost, finalized, (System.nanoTime() - started) / 1_000_000)
            }
            return result()
        }

        if (backgroundLimit != null) return refresh(stored)
        val absent = metrics.any { metric -> range.dates().any { stored.daily[metric.definition.hash]!![it] == null } } ||
            extraDaily.any { (metric, date) -> stored.daily[metric.definition.hash]!![date] == null } || rangeMetrics.any { stored.ranges[rangeKey(it)] == null }
        val copy = stored.copy()
        val task = FutureTask(Callable {
            try { refresh(copy) }
            catch (failure: Exception) { log.warn("admin_analytics_refresh_failed endpoint={} exception={}", endpoint, failure.javaClass.simpleName); throw failure }
        })
        // 종료 중처럼 실행기가 받지 않으면 표식만 풀고 쿨다운 없이 저장 값으로 응답한다.
        try { executor.execute(task) } catch (_: RejectedExecutionException) { state.refreshing.set(false); return result(stored) }
        if (!absent) return result(stored)
        return try { task.get(policy.requestWaitSeconds, TimeUnit.SECONDS) }
        // 갱신이 쓰는 맵 대신 그 시각 DB에 저장된 값만 다시 읽는다.
        catch (_: TimeoutException) { read().also { finalizeReadValues(it) }.let { result(it) } }
        catch (failure: ExecutionException) { throw failure.cause ?: failure }
    }

    /** 배포로 태스크가 끝나면 진행 중 갱신은 기다리지 않고 버린다. 이미 저장한 값은 남는다. */
    @PreDestroy fun close() { executor.shutdownNow() }
}
