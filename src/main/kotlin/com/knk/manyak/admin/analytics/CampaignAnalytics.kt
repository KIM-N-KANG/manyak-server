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
data class CampaignDevices(val ios: Long, val android: Long, val other: Long)
data class CampaignDay(val date: LocalDate, val activeUsers: Long, val newVisitors: CampaignSegments, val newVisitorsBySource: CampaignSources,
    val signups: CampaignSegments, val cumulativeSignups: CampaignSegments, val androidSignups: Long, val storylineRequests: Long, val completionRequests: Long, val chatMessages: Long, val newVisitorsByDevice: CampaignDevices)
data class CampaignTotals(val activeUsersAverage: Double, val newVisitors: CampaignSegments, val signups: CampaignSegments, val androidSignups: Long, val adsShare: Double?,
    val storylineRequests: Long, val completionRequests: Long, val chatMessages: Long, val newVisitorsByDevice: CampaignDevices)
data class CampaignAnalyticsResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val daily: List<CampaignDay>, val totals: CampaignTotals, val pendingDates: List<LocalDate>)
data class CampaignFunnelStep(val event: String, val users: CampaignSegments)
data class CampaignCreationResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val steps: List<CampaignFunnelStep>)
data class StoryCampaignStep(val event: String, val turnNumber: Int?, val users: Long)
data class CampaignStoryResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val storyId: UUID, val steps: List<StoryCampaignStep>)
data class CampaignSourceRow(val source: String?, val newVisitors: Long)
data class CampaignPostRow(val content: String?, val visitors: Long)
data class CampaignSourceResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val sources: List<CampaignSourceRow>, val posts: List<CampaignPostRow>)

data class CampaignActivationStep(val event: String, val minTurnNumber: Int?, val users: CampaignSegments)
data class CampaignActivationResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val steps: List<CampaignActivationStep>)
data class CampaignReachResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val chatUsers: CampaignSegments, val deepChatUsers: CampaignSegments)
data class CreativeUsers(val content: String?, val users: Long)
data class CreativeDay(val date: LocalDate, val signups: List<CreativeUsers>, val deepChatUsers: List<CreativeUsers>)
data class CreativeTotals(val signups: List<CreativeUsers>)
data class CampaignCreativeResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val campaign: String, val pendingDates: List<LocalDate>, val daily: List<CreativeDay>, val totals: CreativeTotals)
data class SourceUsers(val source: String?, val users: Long)
data class AppTransferDay(val date: LocalDate, val bannerShown: Long, val bannerClicked: Long, val bannerClickRate: Double?, val bannerNewUsers: Long, val newUsersBySource: List<SourceUsers>)
data class AppTransferTotals(val bannerNewUsers: Long, val newUsersBySource: List<SourceUsers>)
data class AppTransferResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val pendingDates: List<LocalDate>, val daily: List<AppTransferDay>, val totals: AppTransferTotals)

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
        metrics += metric("signup_all", AdminMetricEvents.SIGNUP)
        metrics += metric("signup_ads", AdminMetricEvents.SIGNUP, common + ads(campaign))
        // Android 앱 가입은 광고 구분 없이 센다.
        metrics += metric("signup_android", AdminMetricEvents.SIGNUP,
            CampaignEvents.common(false) + CampaignEvents.filter("platform", listOf("Android"), "user"))
        for ((id, name) in listOf("storyline" to CampaignEvents.GENERATE, "completion" to CampaignEvents.COMPLETE, "messages" to CampaignEvents.MESSAGE)) metrics += metric(id, name, aggregation = "totals")
        metrics += metric("device_ios", "_new", common + CampaignEvents.filter("device_type", listOf("Apple iPhone", "Apple iPad"), "user"))
        metrics += metric("device_android", "_new", common + CampaignEvents.filter("device_type", listOf("Android"), "user"))
        metrics += metric("device_other", "_new", common + CampaignEvents.filter("device_type", listOf("Apple iPhone", "Apple iPad", "Android"), "user", "is not"))
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
                val signups = CampaignSegments(v[7], v[8])
                cumulativeAll = Math.addExact(cumulativeAll, signups.all); cumulativeAds = Math.addExact(cumulativeAds, signups.ads)
                CampaignDay(date, v[0], CampaignSegments(v[1], v[2]), CampaignSources(v[2], v[3], v[4], v[5], v[6]), signups,
                    CampaignSegments(cumulativeAll, cumulativeAds), v[9], v[10], v[11], v[12], CampaignDevices(v[13], v[14], v[15]))
            }
            fun sum(select: (CampaignDay) -> Long) = daily.fold(0L) { total, row -> Math.addExact(total, select(row)) }
            val visitors = CampaignSegments(sum { it.newVisitors.all }, sum { it.newVisitors.ads })
            val totals = CampaignTotals(daily.map { it.activeUsers.toDouble() }.average(), visitors, CampaignSegments(sum { it.signups.all }, sum { it.signups.ads }), sum { it.androidSignups },
                if (visitors.all == 0L) null else visitors.ads.toDouble() / visitors.all, sum { it.storylineRequests }, sum { it.completionRequests }, sum { it.chatMessages }, CampaignDevices(sum { it.newVisitorsByDevice.ios }, sum { it.newVisitorsByDevice.android }, sum { it.newVisitorsByDevice.other }))
            CampaignAnalyticsResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, campaign, daily, totals, result.pendingDates())
        }
    }
    fun creation(query: CampaignQuery): CampaignCreationResponse {
        val range = query.range(clock, 20, 7, setOf(7)); val campaign = campaign(query)
        val all = queries.funnel("campaign_creation_all", range, CampaignEvents.creation.mapIndexed { i, event -> CampaignEvents.event(event, CampaignEvents.common() + AdminMetricEvents.creationFilters(i)) })
        val ads = queries.funnel("campaign_creation_ads", range, CampaignEvents.creation.mapIndexed { i, event -> CampaignEvents.event(event, CampaignEvents.common() + ads(campaign) + AdminMetricEvents.creationFilters(i)) })
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
    fun activation(query: CampaignQuery): CampaignActivationResponse {
        val range = query.range(clock, 31, 7); val campaign = campaign(query)
        val names = listOf("_active", AdminMetricEvents.MESSAGE, AdminMetricEvents.MESSAGE, AdminMetricEvents.SIGNUP)
        val metrics = listOf(false, true).map { ad -> queries.funnel("campaign_activation_" + (if (ad) "ads" else "all"), range,
            names.mapIndexed { i, name -> CampaignEvents.event(name, CampaignEvents.common() + (if (ad) listOf(ads(campaign)) else emptyList()) +
                if (i == 2) listOf(AdminMetricEvents.filter("turn_number", listOf("5"), op = "greater or equal")) else emptyList()) }, users = "new") }
        val result = snapshots.load("campaigns/funnels/activation", range, emptyList(), rangeMetrics = metrics)
        val values = metrics.map { result.value(it) ?: throw AdminAnalyticsUnavailable() }
        return CampaignActivationResponse(result.asOf(values), range.from, range.to, campaign, names.mapIndexed { i, name ->
            CampaignActivationStep(if (name == AdminMetricEvents.SIGNUP) "signup_succeeded" else name, if (i == 2) 5 else null,
                CampaignSegments(values[0].value[i].asLong(), values[1].value[i].asLong()))
        })
    }
    fun reach(query: CampaignQuery): CampaignReachResponse {
        val range = query.range(clock, 31, 7); val campaign = campaign(query)
        val metrics = listOf(false, true).flatMap { deep -> listOf(false, true).map { ad ->
            queries.collapsed("campaign_reach_${deep}_$ad", range, CampaignEvents.event(AdminMetricEvents.MESSAGE,
                CampaignEvents.common() + (if (ad) listOf(ads(campaign)) else emptyList()) +
                    if (deep) listOf(AdminMetricEvents.filter("turn_number", listOf("5"), op = "greater or equal")) else emptyList()))
        } }
        val result = snapshots.load("campaigns/reach", range, emptyList(), rangeMetrics = metrics)
        val values = metrics.map { result.value(it) ?: throw AdminAnalyticsUnavailable() }
        return CampaignReachResponse(result.asOf(values), range.from, range.to, campaign,
            CampaignSegments(values[0].value.asLong(), values[1].value.asLong()), CampaignSegments(values[2].value.asLong(), values[3].value.asLong()))
    }
    fun creativeMetrics(campaign: String): List<DailyAnalyticsMetric> = listOf(AdminMetricEvents.SIGNUP, AdminMetricEvents.MESSAGE).mapIndexed { i, name ->
        queries.dailyGrouped(if (i == 0) "campaign_creative_signups" else "campaign_creative_deep_chat", CampaignEvents.event(name,
            CampaignEvents.common() + ads(campaign) + if (i == 1) listOf(AdminMetricEvents.filter("turn_number", listOf("5"), op = "greater or equal")) else emptyList()),
            listOf("g" to "gp:initial_utm_content"))
    }
    private fun groupedUsers(value: tools.jackson.databind.JsonNode): List<Pair<String?, Long>> = value.toList().map { row ->
        row["labels"][0].let { if (it.isNull) null else it.asText() } to row["value"].asLong()
    }
    private fun summedGroups(groups: List<List<Pair<String?, Long>>>): List<Pair<String?, Long>> {
        val totals = linkedMapOf<String?, Long>()
        groups.flatten().forEach { (key, n) -> totals[key] = Math.addExact(totals[key] ?: 0L, n) }
        return totals.toList().sortedByDescending { it.second }
    }
    fun creatives(query: CampaignQuery): CampaignCreativeResponse {
        val range = query.range(clock); val campaign = campaign(query); val metrics = creativeMetrics(campaign)
        val result = snapshots.load("campaigns/creatives", range, metrics); val dates = result.readyDates()
        if (dates.isEmpty()) throw AdminAnalyticsUnavailable()
        fun rows(i: Int, date: LocalDate) = groupedUsers(result.value(metrics[i], date)!!.value).sortedByDescending { it.second }.map { CreativeUsers(it.first, it.second) }
        val daily = dates.map { CreativeDay(it, rows(0, it), rows(1, it)) }
        val totals = summedGroups(daily.map { d -> d.signups.map { it.content to it.users } }).map { CreativeUsers(it.first, it.second) }
        return CampaignCreativeResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, campaign, result.pendingDates(), daily, CreativeTotals(totals))
    }
    fun appTransferMetrics(): List<DailyAnalyticsMetric> {
        val web = CampaignEvents.common() + CampaignEvents.filter("device_type", listOf("Android"), "user")
        val android = AdminMetricEvents.platformFilters("android")
        return listOf(queries.daily("app_banner_shown", CampaignEvents.event("client_appBanner_shown", web)),
            queries.daily("app_banner_clicked", CampaignEvents.event("client_appBanner_actionButton_clicked", web)),
            queries.daily("app_banner_new", CampaignEvents.event("_new", android + CampaignEvents.filter("gp:initial_utm_medium", listOf("app_banner"), "user"))),
            queries.dailyGrouped("app_install_sources", CampaignEvents.event("_new", android), listOf("g" to "gp:initial_utm_source")))
    }
    fun appTransfer(query: AnalyticsRangeQuery): AppTransferResponse {
        val range = query.range(clock); val metrics = appTransferMetrics()
        val result = snapshots.load("campaigns/app-transfer", range, metrics); val dates = result.readyDates()
        if (dates.isEmpty()) throw AdminAnalyticsUnavailable()
        val daily = dates.map { date ->
            fun n(i: Int) = result.value(metrics[i], date)!!.value.asLong()
            AppTransferDay(date, n(0), n(1), if (n(0) == 0L) null else n(1).toDouble() / n(0), n(2),
                groupedUsers(result.value(metrics[3], date)!!.value).sortedByDescending { it.second }.map { SourceUsers(it.first, it.second) })
        }
        val sources = summedGroups(daily.map { d -> d.newUsersBySource.map { it.source to it.users } }).map { SourceUsers(it.first, it.second) }
        return AppTransferResponse(result.asOf(dates.flatMap { result.values(it) }), range.from, range.to, result.pendingDates(), daily,
            AppTransferTotals(daily.fold(0L) { sum, d -> Math.addExact(sum, d.bannerNewUsers) }, sources))
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
