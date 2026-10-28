package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.badAdminQuery
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@ConfigurationProperties("manyak.admin.campaigns")
class CampaignSettings(val items: List<CampaignDefinition> = emptyList(), val sourceGroups: Map<String, List<String>> = emptyMap())
data class CampaignDefinition(val key: String, val name: String, val campaign: String, val startDate: LocalDate, val endDate: LocalDate? = null, val storyId: UUID? = null)
open class CampaignQuery : AnalyticsRangeQuery() { var campaign: String? = null }
class StoryCampaignQuery : CampaignQuery() { var storyId: UUID? = null }
data class CampaignSegments(val all: Long, val ads: Long, val nonAds: Long = all - ads)
data class CampaignSources(val ads: Long, val instagram: Long, val threads: Long, val dcinside: Long, val other: Long)
data class CampaignDay(val date: LocalDate, val activeUsers: Long, val newVisitors: CampaignSegments, val newVisitorsBySource: CampaignSources,
    val signups: CampaignSegments, val cumulativeSignups: CampaignSegments, val androidSignups: Long, val storylineRequests: Long, val completionRequests: Long, val chatMessages: Long)
data class CampaignTotals(val activeUsersAverage: Double, val newVisitors: CampaignSegments, val signups: CampaignSegments, val androidSignups: Long, val adsShare: Double?,
    val storylineRequests: Long, val completionRequests: Long, val chatMessages: Long)
data class CampaignAnalyticsResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val daily: List<CampaignDay>, val totals: CampaignTotals, val pendingDates: List<LocalDate>)
data class CampaignFunnelStep(val event: String, val users: CampaignSegments)
data class CampaignCreationResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val steps: List<CampaignFunnelStep>)
data class StoryCampaignStep(val event: String, val turnNumber: Int?, val users: Long)
data class CampaignStoryResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val storyId: UUID, val steps: List<StoryCampaignStep>)
data class CampaignSourceRow(val source: String?, val newVisitors: Long)
data class CampaignPostRow(val content: String?, val visitors: Long)
data class CampaignSourceResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val sources: List<CampaignSourceRow>, val posts: List<CampaignPostRow>)

/** 저장된 차트 ID에 의존하지 않는 이벤트 카탈로그. */
object CampaignEvents {
    const val GENERATE = "client_storyCreate_storyGeneration_requested"
    const val COMPLETE = "client_storyCreate_storyCompletion_requested"
    const val MESSAGE = "client_chat_messageInput_submitted"
    const val GOOGLE = "server_login_googleLogin_processed_succeeded"
    const val KAKAO = "server_login_kakaoLogin_processed_succeeded"
    val creation = AdminMetricEvents.creation
    fun filter(key: String, values: List<String>, type: String = "event", op: String = "is") = mapOf("subprop_type" to type, "subprop_key" to key, "subprop_op" to op, "subprop_value" to values)
    fun event(name: String, filters: List<Map<String, Any>> = emptyList()): Map<String, Any> =
        mapOf("event_type" to name) + if (filters.isEmpty()) emptyMap() else mapOf("filters" to filters)
    fun condition(key: String, values: List<String>, op: String = "is") = mapOf("prop" to key, "op" to op, "values" to values)
    fun common(web: Boolean = true): List<Map<String, Any>> = listOf(filter("country", listOf("South Korea", "(none)"), "user")) +
        if (web) listOf(filter("platform", listOf("Web"), "user")) else emptyList()
}

@Service
@EnableConfigurationProperties(CampaignSettings::class)
class CampaignAnalyticsService(private val settings: CampaignSettings, private val snapshots: AnalyticsSnapshots, private val queries: AnalyticsQueryFactory,
    private val clock: Clock = Clock.systemUTC()) {
    fun catalog() = mapOf("items" to settings.items.sortedBy { it.startDate })
    private fun campaign(query: CampaignQuery): String {
        val value = query.campaign ?: settings.items.lastOrNull { it.endDate == null }?.campaign ?: badAdminQuery("기본 캠페인이 없습니다.")
        if (value.isBlank() || value.length > 100) badAdminQuery("campaign은 1~100자여야 합니다.")
        return value
    }
    private fun ads(campaign: String, op: String = "is") = CampaignEvents.filter("gp:initial_utm_campaign", listOf(campaign), "user", op)
    fun trendMetrics(campaign: String): List<DailyAnalyticsMetric> {
        val common = CampaignEvents.common()
        fun metric(id: String, name: String, filters: List<Map<String, Any>> = common, aggregation: String = "uniques") = queries.daily("campaign_$id", CampaignEvents.event(name, filters), aggregation)
        val metrics = mutableListOf(metric("active", "_active"), metric("new_all", "_new"), metric("new_ads", "_new", common + ads(campaign)))
        for (group in listOf("instagram", "threads", "dcinside", "other")) {
            val values = if (group == "other") settings.sourceGroups.values.flatten().distinct() else settings.sourceGroups[group] ?: error("Missing campaign sources")
            metrics += metric("source_$group", "_new", common + ads(campaign, "is not") +
                CampaignEvents.filter("gp:initial_utm_source", values, "user", if (group == "other") "is not" else "is"))
        }
        val signups = listOf("google" to CampaignEvents.GOOGLE, "kakao" to CampaignEvents.KAKAO)
        for ((provider, name) in signups) {
            val filters = common + CampaignEvents.filter("is_new_user", listOf("true"))
            metrics += metric("signup_${provider}_all", name, filters)
            metrics += metric("signup_${provider}_ads", name, filters + ads(campaign))
        }
        // Android 앱 이벤트에는 첫 유입 UTM이 없어 광고 구분 없이 센다.
        for ((provider, name) in signups) metrics += metric("signup_${provider}_android", name,
            CampaignEvents.common(false) + CampaignEvents.filter("platform", listOf("Android"), "user") + CampaignEvents.filter("is_new_user", listOf("true")))
        for ((id, name) in listOf("storyline" to CampaignEvents.GENERATE, "completion" to CampaignEvents.COMPLETE, "messages" to CampaignEvents.MESSAGE)) metrics += metric(id, name, aggregation = "totals")
        return metrics
    }
    fun analytics(query: CampaignQuery): CampaignAnalyticsResponse {
        val range = query.range(clock); val campaign = campaign(query); val metrics = trendMetrics(campaign)
        val result = snapshots.load("/api/v1/admin/campaigns/analytics", range, metrics)
        val dates = result.readyDates(); if (dates.isEmpty()) throw AdminAnalyticsUnavailable()
        return AmplitudeResults.parse {
            var cumulativeAll = 0L; var cumulativeAds = 0L
            val daily = dates.map { date ->
                val v = metrics.map { result.value(it, date)!!.value.asLong() }
                val signups = CampaignSegments(Math.addExact(v[7], v[9]), Math.addExact(v[8], v[10]))
                cumulativeAll = Math.addExact(cumulativeAll, signups.all); cumulativeAds = Math.addExact(cumulativeAds, signups.ads)
                CampaignDay(date, v[0], CampaignSegments(v[1], v[2]), CampaignSources(v[2], v[3], v[4], v[5], v[6]), signups,
                    CampaignSegments(cumulativeAll, cumulativeAds), Math.addExact(v[11], v[12]), v[13], v[14], v[15])
            }
            fun sum(select: (CampaignDay) -> Long) = daily.fold(0L) { total, row -> Math.addExact(total, select(row)) }
            val visitors = CampaignSegments(sum { it.newVisitors.all }, sum { it.newVisitors.ads })
            val totals = CampaignTotals(daily.map { it.activeUsers.toDouble() }.average(), visitors, CampaignSegments(sum { it.signups.all }, sum { it.signups.ads }), sum { it.androidSignups },
                if (visitors.all == 0L) null else visitors.ads.toDouble() / visitors.all, sum { it.storylineRequests }, sum { it.completionRequests }, sum { it.chatMessages })
            CampaignAnalyticsResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, campaign, daily, totals, result.pendingDates())
        }
    }
    fun creation(query: CampaignQuery): CampaignCreationResponse {
        val range = query.range(clock, 20, 7, setOf(7)); val campaign = campaign(query)
        val all = queries.funnel("campaign_creation_all", range, CampaignEvents.creation.map { CampaignEvents.event(it, CampaignEvents.common()) }, users = "new")
        val ads = queries.funnel("campaign_creation_ads", range, CampaignEvents.creation.map { CampaignEvents.event(it, CampaignEvents.common() + ads(campaign)) }, users = "new")
        val result = snapshots.load("/api/v1/admin/campaigns/funnels/creation", range, emptyList(), rangeMetrics = listOf(all, ads))
        val a = result.value(all) ?: throw AdminAnalyticsUnavailable(); val b = result.value(ads) ?: throw AdminAnalyticsUnavailable()
        return CampaignCreationResponse(result.asOf(listOf(a, b)), range.from, range.to, campaign, CampaignEvents.creation.mapIndexed { i, event ->
            CampaignFunnelStep(event, CampaignSegments(a.value[i].asLong(), b.value[i].asLong()))
        })
    }
    fun story(query: StoryCampaignQuery): CampaignStoryResponse {
        val range = query.range(clock, 31, 7); val campaign = campaign(query)
        val story = query.storyId ?: settings.items.lastOrNull { it.campaign == campaign }?.storyId ?: badAdminQuery("대상 스토리가 없습니다.")
        val names = listOf("client_storyDetail_viewed", "client_storyDetail_chatStartButton_clicked") + List(5) { CampaignEvents.MESSAGE }
        val events = names.mapIndexed { i, name -> CampaignEvents.event(name, CampaignEvents.common() + when (i) {
            0 -> listOf(CampaignEvents.filter("story_id", listOf(story.toString())), CampaignEvents.filter("gp:utm_campaign", listOf(campaign), "user"))
            1 -> listOf(CampaignEvents.filter("story_id", listOf(story.toString())))
            else -> listOf(CampaignEvents.filter("turn_number", listOf((i - 1).toString())))
        }) }
        val metric = queries.funnel("campaign_story_detail", range, events)
        val result = snapshots.load("/api/v1/admin/campaigns/funnels/story-detail", range, emptyList(), rangeMetrics = listOf(metric))
        val value = result.value(metric) ?: throw AdminAnalyticsUnavailable()
        return CampaignStoryResponse(value.fetchedAt, range.from, range.to, campaign, story, names.mapIndexed { i, name -> StoryCampaignStep(name, if (i >= 2) i - 1 else null, value.value[i].asLong()) })
    }
    fun sources(query: AnalyticsRangeQuery): CampaignSourceResponse {
        val range = query.range(clock, 10, 7, setOf(7))
        val source = queries.grouped("campaign_sources", range, CampaignEvents.event("_new", CampaignEvents.common()), listOf("g" to "gp:initial_utm_source"))
        // Amplitude 이벤트 필터의 is + 여러 값은 OR 조건이다. in은 운영에서 HTTP 400(code 229).
        val page = CampaignEvents.event("[Amplitude] Page Viewed", CampaignEvents.common() + CampaignEvents.filter("utm_medium", listOf("community", "social"))) +
            mapOf("group_by" to listOf(mapOf("type" to "event", "value" to "utm_content")))
        val post = queries.grouped("campaign_posts", range, page)
        val result = snapshots.load("/api/v1/admin/campaigns/sources", range, emptyList(), rangeMetrics = listOf(source, post))
        val a = result.value(source) ?: throw AdminAnalyticsUnavailable(); val b = result.value(post) ?: throw AdminAnalyticsUnavailable()
        fun label(row: tools.jackson.databind.JsonNode): String? = row["labels"][0].let { if (it.isNull) null else it.asText() }
        return CampaignSourceResponse(result.asOf(listOf(a, b)), range.from, range.to,
            a.value.toList().map { CampaignSourceRow(label(it), it["value"].asLong()) }.sortedByDescending { it.newVisitors },
            b.value.toList().map { CampaignPostRow(label(it), it["value"].asLong()) }.sortedByDescending { it.visitors })
    }
}
