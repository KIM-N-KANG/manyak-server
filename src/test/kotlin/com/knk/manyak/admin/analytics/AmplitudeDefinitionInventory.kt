package com.knk.manyak.admin.analytics

import org.mockito.Mockito
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.UUID

/** 화면 서비스가 실제로 만드는 요청을 캡처한다. 배치와 화면의 중복은 정의 해시로 합친다. */
internal class AmplitudeDefinitionInventory {
    val mapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-22")
    private val range = AnalyticsRange(date, date)
    data class Entry(val definition: AnalyticsDefinition, val range: AnalyticsRange, val decode: (JsonNode) -> Any, val uses: MutableSet<String>)
    val entries = linkedMapOf<String, Entry>()
    private fun daily(metric: DailyAnalyticsMetric, use: String) { entries.getOrPut(metric.definition.metric + "-" + metric.definition.hash) { Entry(metric.definition, range, { metric.decode(it, range) }, mutableSetOf()) }.uses += use }
    private fun interval(metric: RangeAnalyticsMetric, use: String) { entries.getOrPut(metric.definition.metric + "-" + metric.definition.hash) { Entry(metric.definition, metric.range, metric.decode, mutableSetOf()) }.uses += use }
    private val snapshots = Mockito.mock(AnalyticsSnapshots::class.java) { invocation ->
        if (invocation.method.name == "load") {
            val use = invocation.getArgument<String>(0)
            invocation.getArgument<List<DailyAnalyticsMetric>>(2).forEach { daily(it, use) }
            invocation.getArgument<List<Pair<DailyAnalyticsMetric, LocalDate>>>(3).forEach { daily(it.first, use + ":today") }
            invocation.getArgument<List<RangeAnalyticsMetric>>(4).forEach { interval(it, use) }
            throw Captured()
        }
        Mockito.RETURNS_DEFAULTS.answer(invocation)
    }
    private class Captured : RuntimeException()
    private fun capture(block: () -> Unit) { try { block() } catch (_: Captured) {} }
    init {
        org.springframework.test.util.ReflectionTestUtils.setField(snapshots, "policy", AnalyticsPolicy())
        val queries = AnalyticsQueryFactory(mapper)
        val definitions = MetricDefinitions(queries, mapper)
        val yaml = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")); afterPropertiesSet() }.`object`!!
        val items = (0..20).mapNotNull { i ->
            val p = "manyak.admin.campaigns.items[$i]."
            yaml.getProperty(p + "key")?.let { key -> CampaignDefinition(key, yaml.getProperty(p + "name"), yaml.getProperty(p + "campaign"), LocalDate.parse(yaml.getProperty(p + "start-date")), yaml.getProperty(p + "end-date")?.let(LocalDate::parse), yaml.getProperty(p + "story-id")?.let(UUID::fromString)) }
        }
        val groups = listOf("instagram", "threads", "dcinside").associateWith { group -> (0..10).mapNotNull { yaml.getProperty("manyak.admin.campaigns.source-groups.$group[$it]") } }
        val settings = CampaignSettings(items, groups)
        val dashboard = AdminAnalyticsService(snapshots, AmplitudeDashboardProperties(), mapper, clock)
        val campaigns = CampaignAnalyticsService(settings, snapshots, queries, clock)
        val metrics = AdminMetricsService(definitions, snapshots, queries, modelCodeReads(), com.knk.manyak.admin.config.AdminDashboardProperties(), clock)
        dashboard.dailyMetrics().forEach { daily(it, "dashboard + today") }
        definitions.activity().forEach { daily(it, "users/activity") }; interval(definitions.mau(date), "users/activity:MAU")
        definitions.sessions().forEach { daily(it, "users/sessions") }; definitions.sessionLengths().forEach { daily(it, "users/session-lengths") }
        for (cohort in listOf("visitor", "signup", "first-chat")) definitions.retention(cohort).forEach { daily(it, "users/retention:$cohort") }
        for (platform in listOf("all", "web", "android")) definitions.quality(platform).forEach { daily(it, "quality/$platform") }
        definitions.signupPlatforms().forEach { daily(it, "users/signups") }
        for (segment in listOf("guest", "member")) {
            definitions.sessions(segment).forEach { daily(it, "users/sessions:$segment") }; definitions.sessionLengths(segment).forEach { daily(it, "users/session-lengths:$segment") }
            for (platform in listOf("all", "web", "android")) definitions.quality(platform, segment).forEach { daily(it, "quality/$platform:$segment") }
        }
        for (segment in listOf("all", "guest", "member")) {
            definitions.turnReach(segment).forEach { daily(it, "conversion/turn-reach:$segment") }
            definitions.chatOptions(segment, listOf("AURORA", "LITE")).forEach { daily(it, "quality/chat-options:$segment") }
        }
        for (item in items) {
            campaigns.trendMetrics(item.campaign).forEach { daily(it, "campaign:" + item.key) }
            capture { campaigns.creation(CampaignQuery().apply { from=date.toString(); to=date.toString(); campaign=item.campaign }) }
            val story = item.storyId ?: UUID.fromString("cca6358a-0ef3-4709-88d1-100b9faeeca8")
            capture { campaigns.story(StoryCampaignQuery().apply { from=date.toString(); to=date.toString(); campaign=item.campaign; storyId=story }) }
        }
        capture { campaigns.sources(AnalyticsRangeQuery().apply { from=date.toString(); to=date.toString() }) }
        for (name in listOf("web-first-visit", "android-first-visit", "story-creation", "story-detail-chat", "guest-limit-login")) {
            val platforms = if (name.endsWith("first-visit")) listOf<String?>(null) else listOf("web", "android")
            for (platform in platforms) for (story in if (name == "story-detail-chat") listOf(null, UUID.fromString("cca6358a-0ef3-4709-88d1-100b9faeeca8")) else listOf(null)) capture {
                metrics.funnel(name, ConversionMetricsQuery().apply { from=date.toString(); to=date.toString(); this.platform=platform; storyId=story })
            }
        }
        DefaultAnalyticsBatchCatalog(dashboard, definitions, campaigns, settings, AnalyticsBatchPolicy(), modelCodeReads()).metrics().forEach { daily(it.metric, "batch") }
    }
}
