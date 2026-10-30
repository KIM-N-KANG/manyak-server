package com.knk.manyak.admin.analytics

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.*

@ConfigurationProperties("manyak.admin.analytics.batch")
class AnalyticsBatchPolicy(val enabled: Boolean = true, val cron: String = "0 */5 * * * *",
    val nightlyStart: LocalTime = LocalTime.of(3, 0), val backfillStart: LocalDate = LocalDate.of(2026, 7, 1), val budgetCost: Int = 300) {
    init { require(budgetCost > 0) }
}
data class AnalyticsBatchMetric(val metric: DailyAnalyticsMetric, val start: LocalDate) {
    val key get() = metric.definition.metric + ":" + metric.definition.hash
}
fun interface AnalyticsBatchCatalog { fun metrics(): List<AnalyticsBatchMetric> }

@Component
@EnableConfigurationProperties(AnalyticsBatchPolicy::class)
class DefaultAnalyticsBatchCatalog(private val dashboard: AdminAnalyticsService, private val definitions: MetricDefinitions,
    private val campaigns: CampaignAnalyticsService, private val settings: CampaignSettings, private val policy: AnalyticsBatchPolicy,
    private val reads: AdminMetricReadRepository, private val pushOpens: PushOpensService) : AnalyticsBatchCatalog {
    override fun metrics(): List<AnalyticsBatchMetric> {
        // 턴 도달률과 채팅 옵션은 segment all만 미리 받는다. 모델 코드는 실행 시점의 chat_models다.
        // 리텐션은 방문자 코호트만 미리 받는다.
        val common = dashboard.dailyMetrics() + definitions.activity() + definitions.sessions() + definitions.sessionLengths() +
            listOf("all", "web", "android").flatMap { definitions.quality(it) } + definitions.retention() +
            definitions.signupPlatforms() + listOf(definitions.appActivation()) + campaigns.appTransferMetrics() + definitions.turnReach("all") + definitions.chatOptions("all", reads.modelCodes())
        return (common.map { AnalyticsBatchMetric(it, policy.backfillStart) } + pushOpens.batchMetrics() + settings.items.filter { it.endDate == null }.flatMap { campaign ->
            (campaigns.trendMetrics(campaign.campaign) + campaigns.creativeMetrics(campaign.campaign)).map { AnalyticsBatchMetric(it, maxOf(policy.backfillStart, campaign.startDate)) }
        }).groupBy { it.key }.values.map { entries -> entries.minBy { it.start } }
    }
}
data class AnalyticsBatchResult(val phase: String, val result: String, val reason: String? = null,
    val keys: Int = 0, val fetchedDays: Int = 0, val remainingDays: Int = 0, val cost: Int = 0)

@Component
class AnalyticsBatchRunner(private val catalog: AnalyticsBatchCatalog, private val snapshots: AnalyticsSnapshots,
    private val policy: AnalyticsBatchPolicy, private val clock: Clock = Clock.systemUTC(), private val analyticsPolicy: AnalyticsPolicy = AnalyticsPolicy()) {
    private val retry = mutableMapOf<String, Instant>()
    private data class Work(val entry: AnalyticsBatchMetric, val range: AnalyticsRange)
    private fun work(entries: List<AnalyticsBatchMetric>, today: LocalDate, nightly: Boolean): List<Work> = entries.mapNotNull { entry ->
        val to = today.minusDays(if (nightly) 1 else 2)
        val from = if (!nightly) entry.start else maxOf(entry.start, today.minusDays(if (entry.metric.retention) 33L else 1L + entry.metric.finalizationDelayDays))
        if (from > to) null else Work(entry, AnalyticsRange(from, to))
    }.filter { snapshots.missingDates(it.entry.metric, it.range).isNotEmpty() }

    /** 완료 표식 없이 매번 DB에서 재개할 위치를 결정한다. */
    @Synchronized fun run(): AnalyticsBatchResult? {
        val now = clock.instant().atZone(com.knk.manyak.admin.dto.AdminDateRange.KST)
        val entries = catalog.metrics()
        val nightly = if (now.toLocalTime() >= policy.nightlyStart) work(entries, now.toLocalDate(), true) else emptyList()
        val phase = if (nightly.isNotEmpty()) "nightly" else "backfill"
        val pending = if (nightly.isNotEmpty()) nightly else work(entries, now.toLocalDate(), false)
        if (pending.isEmpty()) return null
        retry.keys.retainAll(entries.map { it.key }.toSet())
        var keys = 0; var fetched = 0; var cost = 0; var reason: String? = null
        for ((entry, range) in pending) {
            if (clock.instant().isBefore(retry[entry.key] ?: Instant.MIN)) continue
            keys++
            try {
                val result = snapshots.load("analytics_batch:" + entry.key, range, listOf(entry.metric), backgroundLimit = policy.budgetCost)
                cost += result.progress.cost; fetched += result.progress.fetchedDays
                val failure = result.progress.failure
                if (failure != null) {
                    reason = if (failure == AnalyticsFailureReason.budget_exceeded) "budget_share" else failure.name
                    if (failure in setOf(AnalyticsFailureReason.budget_exceeded, AnalyticsFailureReason.rate_limited, AnalyticsFailureReason.no_credentials)) break
                    retry[entry.key] = clock.instant().plusSeconds(analyticsPolicy.cooldownSeconds)
                } else if (snapshots.missingDates(entry.metric, range).isNotEmpty()) {
                    reason = "budget_share"
                }
            } catch (_: Exception) {
                // 저장소 등 예상 밖의 실패도 다른 키와 다음 스케줄 실행을 막지 않는다.
                reason = "http_error"
                retry[entry.key] = clock.instant().plusSeconds(analyticsPolicy.cooldownSeconds)
            }
        }
        val remaining = pending.sumOf { snapshots.missingDates(it.entry.metric, it.range).size }
        return AnalyticsBatchResult(phase, if (remaining == 0) "done" else if (fetched > 0 || reason == "budget_share" || reason == null) "partial" else "failed",
            if (remaining == 0) null else reason, keys, fetched, remaining, cost)
    }
}
