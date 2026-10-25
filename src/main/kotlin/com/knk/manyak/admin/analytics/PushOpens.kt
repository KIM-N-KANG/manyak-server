package com.knk.manyak.admin.analytics

import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.dto.PushDeliveryType
import com.knk.manyak.push.service.PushDeliveryService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ConfigurationProperties("manyak.admin.push-opens")
class PushOpensSettings(val measuredFrom: LocalDate? = null)
enum class PushOpenState { MEASURED, BEFORE_MEASUREMENT, NO_LINK, PENDING }
data class PushOpenCampaign(val campaignId: UUID, val kind: PushKind, val title: String, val noticeId: UUID?, val startedAt: Instant,
    val deliveredMembers: Long, val openState: PushOpenState, val pushOpens: Long?, val pushOpenRate: Double?)
data class PushOpenActivity(val type: PushDeliveryType, val deliveredMembers: Long, val openState: PushOpenState, val pushOpens: Long?, val pushOpenRate: Double?)
data class PushOpensResponse(val asOf: Instant?, val pendingDates: List<LocalDate>, val from: LocalDate, val to: LocalDate,
    val measuredFrom: LocalDate?, val campaigns: List<PushOpenCampaign>, val activityFrom: LocalDate?, val activityTypes: List<PushOpenActivity>)

@Service
@EnableConfigurationProperties(PushOpensSettings::class)
class PushOpensService(private val delivery: PushDeliveryService, private val snapshots: AnalyticsSnapshots,
    private val queries: AnalyticsQueryFactory, private val settings: PushOpensSettings, private val clock: Clock = Clock.systemUTC()) {
    fun read(query: AnalyticsRangeQuery): PushOpensResponse {
        val range = query.range(clock, default = 7)
        val measured = settings.measuredFrom
        val activityFrom = measured?.let { maxOf(range.from, it) }?.takeIf { it <= range.to }
        val campaigns = delivery.campaignReach(range.from, range.to)
        val types = listOf(PushDeliveryType.STORY_COMPLETED, PushDeliveryType.STORY_MODERATION_COMPLETED, PushDeliveryType.ATTENDANCE_REMINDER, PushDeliveryType.CREDIT_EXPIRY_REMINDER)
        val reaches = delivery.activityReach(activityFrom ?: range.from, range.to, types).groupBy { it.type }
        val opens = mutableMapOf<String, Long>()
        var asOf: Instant? = null
        var pending = emptyList<LocalDate>()
        if (activityFrom != null) {
            val event = CampaignEvents.event("[Amplitude] Page Viewed", listOf(CampaignEvents.filter("utm_source", listOf("push")))) +
                mapOf("group_by" to listOf(mapOf("type" to "event", "value" to "utm_campaign")))
            val metric = queries.dailyGrouped("push_opens", event)
            val result = snapshots.load("/api/v1/admin/campaigns/push-opens", AnalyticsRange(activityFrom, range.to), listOf(metric))
            val ready = result.readyDates()
            if (ready.isEmpty()) throw AdminAnalyticsUnavailable()
            val known = campaigns.map { it.campaignId.toString() }.toSet() + types.map { it.name.lowercase(java.util.Locale.ROOT) }
            for (date in ready) for (group in result.value(metric, date)!!.value) {
                val label = group["labels"][0].takeUnless { it.isNull }?.asText()
                if (label in known) opens[label!!] = Math.addExact(opens[label] ?: 0, group["value"].asLong())
            }
            asOf = result.asOf(ready.flatMap { result.values(it) })
            pending = result.pendingDates()
        }
        fun rate(opened: Long?, reached: Long) = if (opened == null || reached == 0L) null else opened.toDouble() / reached
        return PushOpensResponse(asOf, pending, range.from, range.to, measured, campaigns.map { campaign ->
            val state = when {
                measured == null || campaign.startedAt.atZone(com.knk.manyak.admin.dto.AdminDateRange.KST).toLocalDate() < measured -> PushOpenState.BEFORE_MEASUREMENT
                campaign.deepLinkIncluded == null -> PushOpenState.PENDING
                !campaign.deepLinkIncluded -> PushOpenState.NO_LINK
                else -> PushOpenState.MEASURED
            }
            val opened = if (state == PushOpenState.MEASURED) opens[campaign.campaignId.toString()] ?: 0 else null
            PushOpenCampaign(campaign.campaignId, campaign.kind, campaign.title, campaign.noticeId, campaign.startedAt,
                campaign.deliveredMembers, state, opened, rate(opened, campaign.deliveredMembers))
        }, activityFrom, types.map { type ->
            val reached = reaches[type].orEmpty().fold(0L) { total, row -> Math.addExact(total, row.deliveredMembers) }
            val opened = if (activityFrom == null) null else opens[type.name.lowercase(java.util.Locale.ROOT)] ?: 0
            PushOpenActivity(type, reached, if (activityFrom == null) PushOpenState.BEFORE_MEASUREMENT else PushOpenState.MEASURED, opened, rate(opened, reached))
        })
    }
}
