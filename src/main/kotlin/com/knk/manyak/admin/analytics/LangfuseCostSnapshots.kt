package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 저장은 기존 분석 값 upsert를 공유하고, 호출 직렬화와 쿨다운은 태스크 전체에 적용한다. */
@Service
class LangfuseCostSnapshots(
    private val store: AnalyticsValueRepository,
    private val client: LangfuseMetricsClient,
    private val properties: AdminLangfuseProperties,
    private val mapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    val definition get() = client.definition
    private val refreshing = AtomicBoolean()
    @Volatile private var retryAt = Instant.MIN
    private var nextCallNanos: Long? = null
    // 응답 예산이 끝나면 미완성 분할 한 묶음만 메모리에 남겨 다음 요청에서 이어받는다.
    // DB에는 완성된 날짜만 저장하며, 재시작 시 미완성 날짜는 다시 조회한다.
    private var pendingBatch: PendingBatch? = null
    private val log = LoggerFactory.getLogger(javaClass)

    private class PendingBatch(val dates: List<LocalDate>, val startedAt: Instant) {
        val intervals = ArrayDeque<LangfuseInterval>().apply { add(LangfuseInterval(AnalyticsRange(dates.last(), dates.first()))) }
        val entries = mutableMapOf<LocalDate, MutableList<LangfuseCostEntry>>()
        val queriedAt = mutableMapOf<LocalDate, Instant>()
        fun remainingDates() = (intervals.flatMap { it.dates() } + entries.keys).toSet()
    }

    fun load(range: AnalyticsRange): Map<LocalDate, LangfuseCostDay> {
        if (!properties.configured()) {
            // 설정 없음도 반복 로그를 억제한다. 저장 값 유무와 무관하게 503이다.
            synchronized(refreshing) {
                if (!clock.instant().isBefore(retryAt)) {
                    retryAt = clock.instant().plus(properties.cooldown)
                    log.warn("admin_ai_cost_fetch_failed reason=not_configured status=null")
                }
            }
            throw AdminAiCostUnavailable()
        }
        val values = store.daily(definition, range.from, range.to).toMutableMap()
        fun result() = values.mapValues { (_, value) -> LangfuseCostDay(
            value.value.toList().map { entry -> LangfuseCostEntry(entry["traceName"].asText(), entry["model"].let { if (it.isNull) null else it.asText() },
                entry["costUsd"].asText().toBigDecimal(), entry["totalTokens"].asLong()) }, value.fetchedAt) }
        fun today() = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
        fun dayEnd(date: LocalDate) = date.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant()
        fun complete(date: LocalDate, value: AnalyticsValue) = value.fetchedAt >= dayEnd(date)
        fun final(date: LocalDate, value: AnalyticsValue) = value.finalizedAt != null ||
            (date <= today().minusDays(2) && complete(date, value))
        fun fresh(date: LocalDate): Boolean {
            val value = values[date] ?: return false
            if (final(date, value)) return true
            val fetchedDay = value.fetchedAt.atZone(AdminDateRange.KST).toLocalDate()
            return when (date) {
                today() -> fetchedDay == date && clock.instant().isBefore(value.fetchedAt.plus(properties.todayTtl))
                today().minusDays(1) -> complete(date, value) && clock.instant().isBefore(value.fetchedAt.plus(properties.yesterdayTtl))
                else -> false
            }
        }
        fun finalizeValues() {
            values.toMap().forEach { (date, value) ->
                if (value.finalizedAt == null && final(date, value)) {
                    store.finalizeDaily(definition, date, value, clock.instant())
                    values[date] = value.copy(finalizedAt = clock.instant())
                }
            }
        }
        finalizeValues()
        if (range.dates().all(::fresh) || clock.instant().isBefore(retryAt) || !refreshing.compareAndSet(false, true)) return result()
        try {
            if (clock.instant().isBefore(retryAt)) return result()
            var calls = 0
            val attempted = mutableSetOf<LocalDate>()
            while (calls < properties.maxCalls) {
                // 다른 태스크가 받은 날짜는 재조회하지 않는다.
                values.putAll(store.daily(definition, range.from, range.to))
                val batch = pendingBatch?.takeIf { batch ->
                    batch.dates.all { it >= range.from && it <= range.to } && batch.remainingDates().all { date ->
                        val now = clock.instant()
                        !fresh(date) && !(batch.startedAt < dayEnd(date) && now >= dayEnd(date)) && when (date) {
                            today() -> now < batch.startedAt.plus(properties.todayTtl)
                            today().minusDays(1) -> now < batch.startedAt.plus(properties.yesterdayTtl)
                            else -> true
                        }
                    }
                } ?: run {
                    pendingBatch = null
                    val missing = range.dates().asReversed().filter { it !in attempted && !fresh(it) }
                    if (missing.isEmpty()) return result()
                    val dates = mutableListOf(missing.first())
                    for (date in missing.drop(1)) {
                        if (dates.size == properties.maxDays || dates.last().minusDays(1) != date) break
                        dates.add(date)
                    }
                    PendingBatch(dates, clock.instant())
                }
                pendingBatch = batch
                attempted.addAll(batch.dates)
                val pending = batch.intervals
                val entries = batch.entries
                val queriedAt = batch.queriedAt
                while (pending.isNotEmpty() && calls < properties.maxCalls) {
                    val interval = pending.removeFirst()
                    nextCallNanos?.let { next ->
                        val remaining = next - System.nanoTime()
                        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                    }
                    val startedAt = clock.instant()
                    calls++
                    val page = try { client.fetch(interval, startedAt) }
                    finally { nextCallNanos = System.nanoTime() + properties.callInterval.toNanos() }
                    if (page.rowLimitReached) {
                        if (interval.hours == 1L) throw LangfuseFetchFailure(AiCostFailureReason.parse_error, 200)
                        val (older, newer) = interval.split()
                        pending.addFirst(older)
                        pending.addFirst(newer)
                        continue
                    }
                    val fetchedAt = clock.instant()
                    page.entries.forEach { (date, rows) ->
                        entries.getOrPut(date) { mutableListOf() }.addAll(rows)
                        queriedAt[date] = minOf(queriedAt[date] ?: startedAt, startedAt)
                    }
                    // 분할 중인 날짜의 일부만 저장하면 다음 요청에서 완성된 하루로 오인한다.
                    val completed = entries.keys.filter { date -> pending.none { date in it.dates() } }
                    val saved = completed.mapNotNull { date ->
                        val rows = entries.remove(date)!!
                        // 자정 전에 시작해 자정 뒤 도착한 날짜는 저장하지 않고 다음 요청에서 다시 받는다.
                        if (queriedAt.getValue(date) < dayEnd(date) && fetchedAt >= dayEnd(date)) return@mapNotNull null
                        val merged = try {
                            rows.groupBy { it.traceName to it.model }.map { (key, parts) ->
                                LangfuseCostEntry(key.first, key.second, parts.sumOf { it.costUsd }, parts.fold(0L) { total, entry -> Math.addExact(total, entry.totalTokens) })
                            }
                        } catch (_: ArithmeticException) { throw LangfuseFetchFailure(AiCostFailureReason.parse_error, 200) }
                        val value = AnalyticsValue(mapper.valueToTree(merged), fetchedAt)
                        date to if (final(date, value)) value.copy(finalizedAt = fetchedAt) else value
                    }.toMap()
                    if (saved.isNotEmpty()) {
                        store.saveDaily(definition, saved)
                        values.putAll(store.daily(definition, batch.dates.last(), batch.dates.first()))
                    }
                }
                if (pending.isEmpty()) pendingBatch = null
            }
        } catch (failure: LangfuseFetchFailure) {
            pendingBatch = null
            retryAt = clock.instant().plus(failure.retryAfter ?: properties.cooldown)
            log.warn("admin_ai_cost_fetch_failed reason={} status={}", failure.reason, failure.status)
        } catch (_: InterruptedException) {
            pendingBatch = null
            Thread.currentThread().interrupt()
            retryAt = clock.instant().plus(properties.cooldown)
            log.warn("admin_ai_cost_fetch_failed reason=http_error status=null")
        } finally { refreshing.set(false) }
        return result()
    }
}
