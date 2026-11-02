package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.dto.PushDeliveryType
import com.knk.manyak.push.dto.PushCampaignReach
import com.knk.manyak.push.service.PushDeliveryService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ConfigurationProperties("manyak.admin.push-opens")
class PushOpensSettings(val measuredFrom: LocalDate? = null, val appMeasuredFrom: LocalDate? = null)
enum class PushOpenState { MEASURED, BEFORE_MEASUREMENT, NO_LINK, PENDING }
data class PushOpenCampaign(val campaignId: UUID, val kind: PushKind, val title: String, val noticeId: UUID?, val startedAt: Instant,
    val deliveredMembers: Long, val openState: PushOpenState, val pushOpens: Long?, val pushOpenRate: Double?,
    val webPushOpens: Long?, val androidPushOpens: Long?, val inboxOpens: Long?, val webInboxOpens: Long?, val androidInboxOpens: Long?,
    val chattedMembers: Long?, val chattedBeforeMembers: Long?, val chatRate: Double?)
data class PushOpenActivity(val type: PushDeliveryType, val deliveredMembers: Long, val openState: PushOpenState, val pushOpens: Long?, val pushOpenRate: Double?,
    val webPushOpens: Long?, val androidPushOpens: Long?, val inboxOpens: Long?, val webInboxOpens: Long?, val androidInboxOpens: Long?,
    val chattedMembers: Long?, val chattedBeforeMembers: Long?, val chatRate: Double?)
data class PushOpensResponse(val asOf: Instant?, val pendingDates: List<LocalDate>, val from: LocalDate, val to: LocalDate,
    val measuredFrom: LocalDate?, val campaigns: List<PushOpenCampaign>, val activityFrom: LocalDate?, val activityTypes: List<PushOpenActivity>, val androidMeasuredFrom: LocalDate?)
data class CampaignPushOpensResponse(val asOf: Instant?, val pendingDates: List<LocalDate>, val campaignId: UUID, val startedAt: Instant?,
    val from: LocalDate?, val to: LocalDate?, val measuredFrom: LocalDate?, val androidMeasuredFrom: LocalDate?, val deliveredMembers: Long,
    val openState: PushOpenState, val pushOpens: Long?, val pushOpenRate: Double?, val webPushOpens: Long?, val androidPushOpens: Long?,
    val inboxOpens: Long?, val webInboxOpens: Long?, val androidInboxOpens: Long?,
    val chattedMembers: Long?, val chattedBeforeMembers: Long?, val chatRate: Double?)

@Service
@EnableConfigurationProperties(PushOpensSettings::class)
class PushOpensService(private val delivery: PushDeliveryService, private val snapshots: AnalyticsSnapshots,
    private val queries: AnalyticsQueryFactory, private val settings: PushOpensSettings, private val clock: Clock = Clock.systemUTC(),
    private val chatConversions: com.knk.manyak.admin.repository.PushChatConversionRepository) {
    private data class OpenMetric(val metric: DailyAnalyticsMetric, val start: LocalDate, val android: Boolean, val inbox: Boolean)
    private data class OpenResult(val asOf: Instant? = null, val pending: List<LocalDate> = emptyList(), val counts: Map<String, Map<String, Long>> = emptyMap())
    private data class Counts(val web: Long?, val app: Long?, val webInbox: Long?, val appInbox: Long?) {
        val push = listOfNotNull(web, app).takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact)
        val inbox = listOfNotNull(webInbox, appInbox).takeIf { it.isNotEmpty() }?.fold(0L, Math::addExact)
        fun rate(reached: Long) = push?.takeIf { reached > 0 }?.toDouble()?.div(reached)
    }
    private data class ChatCounts(val after: Long?, val before: Long?, val rate: Double?)
    private fun conversion(rows: List<com.knk.manyak.admin.repository.PushDeliveryChatActivity>, reached: Long, perDay: Boolean = false): ChatCounts {
        if (reached == 0L) return ChatCounts(null, null, null)
        fun key(row: com.knk.manyak.admin.repository.PushDeliveryChatActivity) =
            (if (perDay) row.delivery.completedAt.atZone(AdminDateRange.KST).toLocalDate() else null) to row.delivery.userId
        val after = rows.filter { it.chatted }.map(::key).distinct().size.toLong()
        val before = rows.filter { it.chattedBefore }.map(::key).distinct().size.toLong()
        return ChatCounts(after, before, after.toDouble() / reached)
    }
    private val types = listOf(PushDeliveryType.STORY_COMPLETED, PushDeliveryType.STORY_MODERATION_COMPLETED, PushDeliveryType.ATTENDANCE_REMINDER, PushDeliveryType.CREDIT_EXPIRY_REMINDER)

    private fun metrics(): List<OpenMetric> = listOf(false, true).flatMap { android ->
        val start = if (android) settings.appMeasuredFrom else settings.measuredFrom
        if (start == null) emptyList() else listOf(false, true).map { inbox ->
            val name = if (!android && !inbox) "push_opens" else if (android) { if (inbox) "android_inbox_opens" else "android_push_opens" } else "web_inbox_opens"
            val event = CampaignEvents.event(if (android) "client_notification_opened" else "[Amplitude] Page Viewed",
                listOf(CampaignEvents.filter("utm_source", listOf(if (inbox) "inbox" else "push")))) +
                mapOf("group_by" to listOf(mapOf("type" to "event", "value" to "utm_campaign")))
            OpenMetric(queries.dailyGrouped(name, event), start, android, inbox)
        }
    }
    /** 화면과 배치는 같은 정의를 쓰고 서비스 시작일 대신 플랫폼 측정 시작일부터 채운다. */
    fun batchMetrics(): List<AnalyticsBatchMetric> = metrics().map { AnalyticsBatchMetric(it.metric, it.start) }
    private fun measured(start: LocalDate?, android: Boolean) = start != null && (if (android) settings.appMeasuredFrom else settings.measuredFrom)?.let { start >= it } == true
    private fun state(row: PushCampaignReach): PushOpenState {
        val start = row.startedAt?.atZone(AdminDateRange.KST)?.toLocalDate() ?: return PushOpenState.PENDING
        return when {
            !measured(start, false) && !measured(start, true) -> PushOpenState.BEFORE_MEASUREMENT
            row.deepLinkIncluded == null -> PushOpenState.PENDING
            !row.deepLinkIncluded -> PushOpenState.NO_LINK
            else -> PushOpenState.MEASURED
        }
    }
    private fun load(range: AnalyticsRange, specs: List<OpenMetric>): OpenResult {
        val active = specs.filter { it.start <= range.to }
        if (active.isEmpty()) return OpenResult()
        val from = maxOf(range.from, active.minOf { it.start })
        val result = snapshots.load("/api/v1/admin/campaigns/push-opens", AnalyticsRange(from, range.to), active.map { it.metric },
            dailyFrom = active.associate { it.metric.definition.hash to maxOf(range.from, it.start) })
        val ready = result.readyDates()
        if (ready.isEmpty()) throw AdminAnalyticsUnavailable()
        val counts = active.associate { spec ->
            val values = mutableMapOf<String, Long>()
            for (date in ready.filter { it >= spec.start }) for (group in result.value(spec.metric, date)!!.value) {
                val label = group["labels"][0].takeUnless { it.isNull }?.asText() ?: continue
                values[label] = Math.addExact(values[label] ?: 0, group["value"].asLong())
            }
            spec.metric.definition.metric to values
        }
        return OpenResult(result.asOf(ready.flatMap { result.values(it) }), result.pendingDates(), counts)
    }
    private fun counts(result: OpenResult, label: String, start: LocalDate?, state: PushOpenState): Counts {
        fun value(android: Boolean, inbox: Boolean): Long? {
            if (state != PushOpenState.MEASURED || !measured(start, android)) return null
            if (inbox && label == "credit_expiry_reminder") return 0
            val name = if (!android && !inbox) "push_opens" else if (android) { if (inbox) "android_inbox_opens" else "android_push_opens" } else "web_inbox_opens"
            return result.counts[name]?.get(label) ?: 0
        }
        return Counts(value(false, false), value(true, false), value(false, true), value(true, true))
    }
    fun read(query: AnalyticsRangeQuery): PushOpensResponse {
        val range = query.range(clock, default = 7)
        val activityFrom = listOfNotNull(settings.measuredFrom, settings.appMeasuredFrom).minOrNull()?.let { maxOf(range.from, it) }?.takeIf { it <= range.to }
        val campaigns = delivery.campaignReach(range.from, range.to)
        val reaches = delivery.activityReach(activityFrom ?: range.from, range.to, types).groupBy { it.type }
        val activityChats = chatConversions.activity(delivery.reachedMemberDeliveries(activityFrom ?: range.from, range.to, types)).groupBy { it.delivery.type }
        val campaignChats = chatConversions.activity(delivery.reachedMemberDeliveries(campaigns.map { it.campaignId })).groupBy { it.delivery.campaignId }
        val result = load(range, metrics())
        return PushOpensResponse(result.asOf, result.pending, range.from, range.to, settings.measuredFrom, campaigns.map { row ->
            val state = state(row)
            val n = counts(result, row.campaignId.toString(), row.startedAt?.atZone(AdminDateRange.KST)?.toLocalDate(), state)
            val chats = conversion(campaignChats[row.campaignId].orEmpty(), row.deliveredMembers)
            PushOpenCampaign(row.campaignId, row.kind, row.title, row.noticeId, requireNotNull(row.startedAt), row.deliveredMembers, state,
                n.push, n.rate(row.deliveredMembers), n.web, n.app, n.inbox, n.webInbox, n.appInbox, chats.after, chats.before, chats.rate)
        }, activityFrom, types.map { type ->
            val reached = reaches[type].orEmpty().fold(0L) { total, row -> Math.addExact(total, row.deliveredMembers) }
            val state = if (activityFrom == null) PushOpenState.BEFORE_MEASUREMENT else PushOpenState.MEASURED
            val n = counts(result, type.name.lowercase(java.util.Locale.ROOT), activityFrom, state)
            val chats = conversion(activityChats[type].orEmpty(), reached, perDay = true)
            PushOpenActivity(type, reached, state, n.push, n.rate(reached), n.web, n.app, n.inbox, n.webInbox, n.appInbox, chats.after, chats.before, chats.rate)
        }, settings.appMeasuredFrom)
    }
    fun campaign(id: UUID): CampaignPushOpensResponse {
        val row = delivery.campaignReach(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "캠페인이 없습니다.")
        val from = row.startedAt?.atZone(AdminDateRange.KST)?.toLocalDate()
        val to = from?.let { minOf(LocalDate.now(clock.withZone(AdminDateRange.KST)), it.plusDays(365)) }
        val state = state(row)
        val result = if (state == PushOpenState.MEASURED) load(AnalyticsRange(requireNotNull(from), requireNotNull(to)), metrics().filter { measured(from, it.android) }) else OpenResult()
        val n = counts(result, id.toString(), from, state)
        val chats = conversion(chatConversions.activity(delivery.reachedMemberDeliveries(id)), row.deliveredMembers)
        return CampaignPushOpensResponse(result.asOf, result.pending, id, row.startedAt, from, to, settings.measuredFrom, settings.appMeasuredFrom,
            row.deliveredMembers, state, n.push, n.rate(row.deliveredMembers), n.web, n.app, n.inbox, n.webInbox, n.appInbox, chats.after, chats.before, chats.rate)
    }
}
