package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import java.time.*
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE

class AppOverseasMetricsTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var definitions: MetricDefinitions
    private lateinit var queries: AnalyticsQueryFactory
    private lateinit var service: AdminMetricsService
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private val today = LocalDate.parse("2026-10-23")
    private var dashboardValues = false
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z")))
        queries = AnalyticsQueryFactory(fixture.mapper); definitions = MetricDefinitions(queries, fixture.mapper)
        service = AdminMetricsService(definitions, fixture.snapshots, queries, modelCodeReads(), com.knk.manyak.admin.config.AdminDashboardProperties(), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val u = request.requestUrl!!
                val from = LocalDate.parse(u.queryParameter("start"), BASIC_ISO_DATE)
                val to = LocalDate.parse(u.queryParameter("end"), BASIC_ISO_DATE)
                val dates = AnalyticsRange(from, to).dates()
                val e = u.queryParameter("e")?.let { fixture.mapper.readTree(it) }
                val group = condition(e ?: u.queryParameter("se")?.let { fixture.mapper.readTree(it) })
                val data: Any = when {
                    u.encodedPath.endsWith("/retention") -> mapOf("series" to listOf(mapOf("values" to dates.associate { date -> date.toString() to List(32) { i ->
                        mapOf("count" to (if (i == 0) { if (group == "legacy_all") 20 else if (group == "android") 5 else 15 } else if (group == "legacy_all") 5 else if (group == "android") 1 else 4), "outof" to (if (group == "legacy_all") 20 else if (group == "android") 5 else 15), "incomplete" to (group == "android" && i == 8))
                    } })))
                    u.encodedPath.endsWith("/average") -> mapOf("xValues" to dates.map { "${it}T00:00:00" }, "series" to listOf(dates.map { 12.5 }))
                    u.encodedPath.endsWith("/funnels") -> listOf(mapOf("cumulativeRaw" to List(u.queryParameterValues("e").size) { 1 }))
                    else -> {
                        val event = e!!["event_type"].asText()
                        val value: Number = when {
                            u.queryParameter("m") == "average" -> 4.5
                            event == "_active" -> if (dashboardValues) when (group) { "web" -> 200; "android" -> 18; "overseas" -> 2; else -> 999 } else when (group) { "all" -> 11; "web" -> 10; "android" -> 2; "overseas" -> 1; "unknown" -> 2; else -> 999 }
                            event == "_new" -> if (dashboardValues) when (group) { "web" -> 30; "android" -> 4; else -> 999 } else when (group) { "android" -> 1; else -> 5 }
                            else -> {
                                val mode = e["filters"].firstOrNull { it["subprop_key"].asText() == "input_mode" }?.get("subprop_value")?.get(0)?.asText()
                                val values = when (group) { "android" -> listOf(10,6,2,1,1,2); "overseas" -> listOf(5,3,1,0,1,1); else -> listOf(100,60,25,10,5,7) }
                                when { event == AdminMetricEvents.MESSAGE -> values[when (mode) { "choice" -> 1; "plain" -> 2; "block" -> 3; else -> 0 }]; event == "client_chat_streamError_shown" -> values[4]; else -> values[5] }
                            }
                        }
                        val collapsed = when (group) { "all" -> 1100; "android" -> 90; "overseas" -> 10; else -> 999 }
                        mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(dates.map { value }), "seriesCollapsed" to listOf(listOf(mapOf("value" to collapsed))))
                    }
                }
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun condition(e: JsonNode?): String {
        val fs = e?.get("filters")?.toList().orEmpty()
        val p = fs.firstOrNull { it["subprop_key"].asText() == "platform" }
        return when {
            fs.any { it["subprop_key"].asText() == "user_id" } -> "overseas"
            p == null -> "legacy_all"
            p["subprop_op"].asText() == "is not" -> "all"
            else -> when (p["subprop_value"][0].asText()) { "Web" -> "web"; "Android" -> "android"; "(none)" -> "unknown"; else -> error("unexpected platform") }
        }
    }
    private fun range(from: LocalDate = today.minusDays(6), to: LocalDate = today) = AnalyticsRangeQuery().apply { this.from=from.toString(); this.to=to.toString() }
    private fun event(request: RecordedRequest, key: String = "e") = fixture.mapper.readTree(request.requestUrl!!.queryParameter(key)!!)
    private fun legacyFilters(platform: String) = listOf(AdminMetricEvents.filter("country", listOf("South Korea", "(none)"), "user")) +
        if (platform == "all") emptyList() else listOf(AdminMetricEvents.filter("platform", listOf(when(platform) { "web" -> "Web"; "android" -> "Android"; else -> "(none)" }), "user"))
    private fun assertOverseas(e: JsonNode) {
        assertThat(e["filters"].filter { it["subprop_type"].asText() == "user" }).containsExactly(
            fixture.mapper.readTree("""{"subprop_type":"user","subprop_key":"country","subprop_op":"is not","subprop_value":["South Korea","(none)"]}"""),
            fixture.mapper.readTree("""{"subprop_type":"user","subprop_key":"platform","subprop_op":"is","subprop_value":["Android"]}"""),
            fixture.mapper.readTree("""{"subprop_type":"user","subprop_key":"user_id","subprop_op":"is not","subprop_value":["(none)"]}""")
        )
    }
    @Test fun `개요는 활성 세 조건 신규 두 조건을 받아 합계 220과 34를 계산한다`() {
        dashboardValues = true
        val value = fixture.service.dashboard(7)
        assertThat(requests).hasSize(5)
        assertThat(value.daily.first().activeUsers).isEqualTo(DashboardPlatformCounts(220,200,20))
        assertThat(value.daily.first().newUsers).isEqualTo(DashboardPlatformCounts(34,30,4))
        assertThat(value.todayActiveUsers).isEqualTo(value.daily.last().activeUsers)
        assertThat(requests.map { condition(event(it)) }).containsExactly("web","android","overseas","web","android")
        assertOverseas(event(requests[2]))
        assertThat(requests[2].requestUrl!!.queryParameter("s")).isNull()
        assertThat(fixture.budget.reserve(465)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `이전 국내 웹과 앱 저장값은 해시가 같아 외국 로그인 앱만 새로 받는다`() {
        for (name in listOf("active", "new")) for (platforms in listOf(listOf("Web","Android"),listOf("Web"),listOf("Android"))) {
            val filters = listOf(AdminMetricEvents.filter("country",listOf("South Korea","(none)"),"user"),AdminMetricEvents.filter("platform",platforms,"user"))
            val metric = queries.daily("dashboard_$name",AdminMetricEvents.event("_$name",filters))
            fixture.store.saveDaily(metric.definition,AnalyticsRange(today.minusDays(6),today).dates().associateWith {
                AnalyticsValue(fixture.mapper.valueToTree(8),fixture.clock.instant(),fixture.clock.instant())
            })
        }
        val value = fixture.service.dashboard(7)
        assertThat(requests).hasSize(1); assertOverseas(event(requests.single()))
        assertThat(value.todayActiveUsers).isEqualTo(DashboardPlatformCounts(17,8,9))
        assertThat(value.daily.first().newUsers).isEqualTo(DashboardPlatformCounts(16,8,8))
        assertThat(fixture.service.dashboard(7)).isEqualTo(value); assertThat(requests).hasSize(1)
    }
    @Test fun `활동 14회와 MAU 90 비용 및 겹치는 플랫폼을 단순 합과 구분한다`() {
        val q = range(today.minusDays(8),today.minusDays(2))
        val value = service.activity(q)
        assertThat(requests).hasSize(14)
        assertThat(value.monthlyActiveUsers).isEqualTo(1200)
        assertThat(value.daily.first().activeUsers).isEqualTo(PlatformCounts(14,10,3,2))
        assertThat(value.daily.first().newVisitors).isEqualTo(PlatformCounts(6,5,1,5))
        assertThat(value.daily.last().cumulativeNewVisitors.all).isEqualTo(42)
        assertThat(value.daily.first().messagesPerUser).isEqualTo(PlatformDecimals(null,4.5,4.5))
        val mau = requests.take(3)
        assertThat(mau.map { condition(event(it)) }).containsExactly("all","android","overseas")
        mau.forEach { assertThat(it.requestUrl!!.queryParameter("start")).isEqualTo("20260922"); assertThat(it.requestUrl!!.queryParameter("end")).isEqualTo("20261021") }
        val overseas = requests.filter { condition(event(it)) == "overseas" }
        assertThat(overseas).hasSize(2); overseas.forEach { assertOverseas(event(it)); assertThat(event(it)["event_type"].asText()).isEqualTo("_active") }
        assertThat(fixture.budget.reserve(333)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        assertThat(service.activity(q)).isEqualTo(value); assertThat(requests).hasSize(14)
        assertThat(service.activity(range(today.minusDays(6),today.minusDays(2))).monthlyActiveUsers).isEqualTo(1200)
        assertThat(requests).hasSize(14)
    }
    @Test fun `리텐션 전체는 플랫폼 제한 없는 국내 조회를 유지하고 미완료는 null이다`() {
        val value = service.retention(range(today.minusDays(4),today.minusDays(4)))
        assertThat(requests).hasSize(3)
        val all = value.platforms.first()
        assertThat(all.cohorts.single().users).isEqualTo(20)
        assertThat(all.cohorts.single().d1).isEqualTo(0.25)
        assertThat(all.summary.d1).isEqualTo(0.25)
        assertThat(all.cohorts.single().d7).isNull(); assertThat(all.cohorts.single().d30).isNull()
        assertThat(requests.map { condition(event(it,"se")) }).containsExactly("legacy_all","web","android")
        requests.forEach { assertThat(it.requestUrl!!.toString()).doesNotContain("user_id"); assertThat(it.requestUrl!!.queryParameter("s")).isNull() }
        assertThat(fixture.budget.reserve(476)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @ParameterizedTest @ValueSource(strings=["web","android","all"])
    fun `기능 지표는 조건 묶음마다 수집하고 합한 뒤 잔여 입력 방식과 오류율을 계산한다`(platform: String) {
        val q = PlatformMetricsQuery().apply { from="2026-10-17"; to="2026-10-23"; this.platform=platform }
        val value = service.quality(q)
        val groups = when(platform) { "web" -> 1; "android" -> 2; else -> 3 }
        assertThat(requests).hasSize(groups*11)
        val row = value.daily.first()
        val messages = when(platform) { "web" -> 100; "android" -> 15; else -> 115 }
        val errors = when(platform) { "web" -> 5; "android" -> 2; else -> 7 }
        assertThat(row.streamErrors).isEqualTo(errors.toLong()); assertThat(row.streamErrorRate).isEqualTo(errors.toDouble()/messages)
        assertThat(row.messagesByInputMode["unknown"]).isEqualTo(when(platform) { "web" -> 5L; "android" -> 2L; else -> 7L })
        assertThat(row.users.values).containsOnly(when(platform) { "web" -> 7L; "android" -> 3L; else -> 10L })
        requests.filter { condition(event(it)) == "overseas" }.forEach { assertOverseas(event(it)) }
        assertThat(requests.count { condition(event(it)) == "overseas" }).isEqualTo(if(platform=="web") 0 else 11)
        assertThat(fixture.budget.reserve(500-groups*77)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `바뀌지 않은 조회는 정의의 metric params hash를 유지한다`() {
        val activity = definitions.activity().map { it.definition }
        for (platform in listOf("web","android","unknown")) for (event in listOf("active","new")) {
            val old = queries.daily("activity_$event",AdminMetricEvents.event("_$event",legacyFilters(platform))).definition
            val current = activity.single { it.metric==old.metric && it.hash==old.hash }
            assertThat(current.params).isEqualTo(old.params)
        }
        for (platform in listOf("web","android")) {
            val old = queries.daily("activity_messages",AdminMetricEvents.event(AdminMetricEvents.MESSAGE,legacyFilters(platform)),"average",false).definition
            assertThat(activity.map { it.hash }).contains(old.hash)
            val expected = AnalyticsDefinition("users_retention","/api/2/retention",listOf("se" to fixture.mapper.writeValueAsString(AdminMetricEvents.event("_new",legacyFilters(platform))),"re" to fixture.mapper.writeValueAsString(AdminMetricEvents.event("_active",legacyFilters(platform))),"i" to "1"),8,fixture.mapper)
            assertThat(definitions.retention().map { it.definition.hash }).contains(expected.hash)
            val oldQuality = queries.daily("quality_messages",AdminMetricEvents.event(AdminMetricEvents.MESSAGE,legacyFilters(platform)),"totals").definition
            assertThat(definitions.quality(platform).map { it.definition.hash }).contains(oldQuality.hash)
        }
        val oldRetentionAll = AnalyticsDefinition("users_retention","/api/2/retention", listOf(
            "se" to fixture.mapper.writeValueAsString(AdminMetricEvents.event("_new", legacyFilters("all"))),
            "re" to fixture.mapper.writeValueAsString(AdminMetricEvents.event("_active", legacyFilters("all"))), "i" to "1"),8,fixture.mapper)
        assertThat(definitions.retention().first().definition.params).isEqualTo(oldRetentionAll.params)
        assertThat(definitions.retention().first().definition.hash).isEqualTo(oldRetentionAll.hash)
        val cohort = fixture.mapper.writeValueAsString(listOf(mapOf("prop" to "userdata_cohort", "op" to "is not", "values" to listOf("team"))))
        val dashboard = AdminAnalyticsService(fixture.snapshots,AmplitudeDashboardProperties(teamCohortId="team"),fixture.mapper,fixture.clock)
        for(platform in listOf("web","android")) for(name in listOf("active","new")) {
            val old = AnalyticsDefinition("dashboard_$name","/api/2/events/segmentation",listOf(
                "e" to fixture.mapper.writeValueAsString(AdminMetricEvents.event("_$name",legacyFilters(platform))), "m" to "uniques", "i" to "1", "s" to cohort),2,fixture.mapper)
            assertThat(dashboard.dailyMetrics().map { it.definition.hash }).contains(old.hash)
        }
        val oldAll = queries.daily("activity_active",AdminMetricEvents.event("_active",legacyFilters("all"))).definition
        assertThat(activity.map { it.hash }).doesNotContain(oldAll.hash)
        val oldSessions = listOf("all","web","android").map { p ->
            val s = listOf(AdminMetricEvents.condition("country",listOf("South Korea","(none)"))) + if(p=="all") emptyList() else listOf(AdminMetricEvents.condition("platform",listOf(if(p=="web") "Web" else "Android")))
            AnalyticsDefinition("users_session_average","/api/2/sessions/average",listOf("i" to "1","s" to fixture.mapper.writeValueAsString(s)),if(p=="all")8 else 12,fixture.mapper).hash
        }
        assertThat(definitions.sessions().map { it.definition.hash }).containsExactlyElementsOf(oldSessions)
    }
    @Test fun `새벽 카탈로그는 네 기능 조건과 플랫폼 지표를 중복 없이 포함하고 하루 199와 리텐션 792다`() {
        val settings = CampaignSettings(listOf(CampaignDefinition("ongoing","진행","campaign",today.minusDays(30))), mapOf("instagram" to listOf("ig"),"threads" to listOf("th"),"dcinside" to listOf("dc")))
        val dashboard = AnalyticsTestFixture(AmplitudeDashboardProperties(teamCohortId="team"),fixture.clock)
        val campaigns = CampaignAnalyticsService(settings,fixture.snapshots,queries,fixture.clock)
        val entries = DefaultAnalyticsBatchCatalog(dashboard.service,definitions,campaigns,settings,AnalyticsBatchPolicy(),modelCodeReads()).metrics()
        assertThat(entries.map { it.key }).doesNotHaveDuplicates()
        assertThat(entries.filterNot { it.metric.retention }.sumOf { it.metric.definition.perDay }).isEqualTo(199)
        assertThat(entries.filter { it.metric.retention }.sumOf { it.metric.definition.perDay }*33).isEqualTo(792)
        assertThat(entries.filter { it.metric.definition.metric.startsWith("quality_") && it.metric.definition.metric !in setOf("quality_chat_starts", "quality_model_turns") }).hasSize(44)
        // 분석 지표 10, 사용자 활동 11, 세션 32, 기능 사용 48, 캠페인 추이 16, 신규 가입자 플랫폼 6, 턴 도달률 플랫폼 36, 모델 둘의 채팅 옵션 플랫폼 12
        assertThat(entries.filter { it.metric.definition.metric == "users_signup_platform" }).hasSize(6)
        assertThat(entries.filter { it.metric.definition.metric == "conversion_turn_reach" }).hasSize(36)
        assertThat(entries.filter { it.metric.definition.metric in setOf("quality_chat_starts", "quality_model_turns") }).hasSize(12)
        assertThat(entries.map { it.metric.definition.metric }).noneMatch { it.contains("mau") || it.contains("push_opens") || (it.startsWith("conversion_") && it != "conversion_turn_reach") }
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("true,false,2,2", "false,true,1,2", "true,true,2,3")
    fun `같은 회원이 국내 해외 앱과 웹 조건을 넘나들면 조건별로 세고 해외 비로그인은 제외한다`(domesticApp: Boolean, web: Boolean, expectedAndroid: Long, expectedAll: Long) {
        val rows = listOf(
            mapOf("id" to "member-a", "date" to "2026-10-22", "country" to "South Korea", "platform" to "Android", "user_id" to "a"),
            mapOf("id" to "member-a", "date" to "2026-10-23", "country" to "South Korea", "platform" to "Android", "user_id" to "a"),
            mapOf("id" to "member-a", "date" to "2026-10-23", "country" to "United States", "platform" to "Android", "user_id" to "a"),
            mapOf("id" to "member-a", "date" to "2026-10-23", "country" to "South Korea", "platform" to "Web", "user_id" to "a"),
            mapOf("id" to "test-device", "date" to "2026-10-23", "country" to "United States", "platform" to "Android", "user_id" to "(none)"),
            mapOf("id" to "foreign-web", "date" to "2026-10-23", "country" to "United States", "platform" to "Web", "user_id" to "b")
        ).filter { domesticApp || !(it["country"]=="South Korea" && it["platform"]=="Android") }
            .filter { web || !(it["country"]=="South Korea" && it["platform"]=="Web") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val u = request.requestUrl!!; val e = event(request)
                val from = LocalDate.parse(u.queryParameter("start"),BASIC_ISO_DATE); val to = LocalDate.parse(u.queryParameter("end"),BASIC_ISO_DATE)
                val dates = AnalyticsRange(from,to).dates()
                fun count(dates: List<LocalDate>) = if(e["event_type"].asText() != "_active") 0 else rows.filter { row ->
                    LocalDate.parse(row.getValue("date")) in dates && e["filters"].all { f ->
                        val present = f["subprop_value"].toList().map { it.asText() }.contains(row[f["subprop_key"].asText()] ?: "(none)")
                        if(f["subprop_op"].asText() == "is not") !present else present
                    }
                }.map { it.getValue("id") }.distinct().size
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() },
                    "series" to listOf(dates.map { if(u.queryParameter("m")=="average") 0 else count(listOf(it)) }), "seriesCollapsed" to listOf(listOf(mapOf("value" to count(dates))))))))
            }
        }
        val value = service.activity(range(today.minusDays(1)))
        assertThat(value.monthlyActiveUsers).isEqualTo(expectedAll)
        assertThat(value.daily.last().activeUsers).isEqualTo(PlatformCounts(expectedAll,if(web)1 else 0,expectedAndroid,0))
        assertThat(value.daily.last().newVisitors).isEqualTo(PlatformCounts(0,0,0,0))
        val dashboard = fixture.service.dashboard(7)
        assertThat(dashboard.todayActiveUsers).isEqualTo(DashboardPlatformCounts(expectedAll,if(web)1 else 0,expectedAndroid))
        assertThat(dashboard.daily.last().newUsers).isEqualTo(DashboardPlatformCounts(0,0,0))
    }
    @Test fun `웹에서 처음 방문하고 앱으로 복귀해도 플랫폼 제한 없는 전체 리텐션은 복귀로 센다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val group = condition(event(request,"se"))
                val points = List(32) { i -> mapOf("outof" to if(group=="android") 0 else 1,
                    "count" to if(group=="legacy_all" || (group=="web" && i<=1)) 1 else 0,"incomplete" to false) }
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("series" to listOf(mapOf("values" to mapOf("2026-10-19" to points)))))))
            }
        }
        val value = service.retention(range(today.minusDays(4),today.minusDays(4)))
        assertThat(value.platforms[0].cohorts.single().d1).isEqualTo(1.0)
        assertThat(value.platforms[1].cohorts.single().d1).isEqualTo(0.0)
        assertThat(value.platforms[2].cohorts.single().d1).isNull()
        for (key in listOf("se","re")) assertThat(event(requests.first(),key)["filters"].toString()).doesNotContain("platform","user_id")
    }
    @Test fun `앱 첫 접속 퍼널은 국내 조건만 쓰며 로그인 조건을 넣지 않는다`() {
        service.funnel("android-first-visit",ConversionMetricsQuery().apply { from=today.toString();to=today.toString() })
        assertThat(requests).hasSize(1)
        val events = requests.single().requestUrl!!.queryParameterValues("e").map { fixture.mapper.readTree(it!!) }
        assertThat(events).hasSize(2)
        for (event in events) assertThat(event["filters"]).isEqualTo(fixture.mapper.valueToTree<JsonNode>(legacyFilters("android")))
        assertThat(fixture.budget.reserve(496)).isTrue();assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `합 구성 조회가 실패하면 준비된 날짜만 반환하고 남은 묶음만 더하지 않는다`() {
        val metrics = definitions.quality("all")
        for ((index,metric) in metrics.withIndex()) {
            val zero = fixture.mapper.readTree(metric.definition.parameters.first { it.first=="e" }.second)["filters"].any { it["subprop_key"].asText()=="input_mode" }
            val dates = if(index<22) listOf(today.minusDays(1),today) else listOf(today.minusDays(1))
            fixture.store.saveDaily(metric.definition,dates.associateWith { AnalyticsValue(fixture.mapper.valueToTree(if(zero)0 else 2),fixture.clock.instant()) })
        }
        val working = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request; return MockResponse().setResponseCode(500)
        } }
        val value = service.quality(PlatformMetricsQuery().apply { from=today.minusDays(1).toString();to=today.toString() })
        assertThat(value.pendingDates).containsExactly(today)
        assertThat(value.daily.single().date).isEqualTo(today.minusDays(1))
        assertThat(value.daily.single().users.values).containsOnly(6L)
        assertThat(requests).hasSize(1)
        assertThat(service.quality(PlatformMetricsQuery().apply { from=today.minusDays(1).toString();to=today.toString() })).isEqualTo(value)
        assertThat(requests).hasSize(1)
        fixture.clock.at=fixture.clock.at.plusSeconds(61); server.dispatcher=working
        val recovered=service.quality(PlatformMetricsQuery().apply { from=today.minusDays(1).toString();to=today.toString() })
        assertThat(recovered.pendingDates).isEmpty(); assertThat(recovered.daily).hasSize(2)
        assertThat(requests).hasSize(12)
        requests.drop(1).forEach { assertOverseas(event(it)) }
    }
    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `해외 로그인 조회 실패와 준비된 날짜 없음은 국내 값이 있어도 503이다`(cached: Boolean) {
        if(cached) fixture.service.dailyMetrics().filter { !it.definition.params.contains("user_id") }.forEach { metric ->
            fixture.store.saveDaily(metric.definition,mapOf(today to AnalyticsValue(fixture.mapper.valueToTree(2),fixture.clock.instant())))
        }
        val working = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            return if(condition(event(request))=="overseas") { requests += request; MockResponse().setResponseCode(500) } else working.dispatch(request)
        } }
        assertThatThrownBy { fixture.service.dashboard(AdminChartsRequest().apply { from=today.toString();to=today.toString() }) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        val failedCalls=if(cached)1 else 3
        assertThat(requests).hasSize(failedCalls); assertOverseas(event(requests.last()))
        assertThatThrownBy { fixture.service.dashboard(AdminChartsRequest().apply { from=today.toString();to=today.toString() }) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        fixture.clock.at=fixture.clock.at.plusSeconds(61); server.dispatcher=working
        val recovered=fixture.service.dashboard(AdminChartsRequest().apply { from=today.toString();to=today.toString() })
        assertThat(recovered.todayActiveUsers).isEqualTo(if(cached) DashboardPlatformCounts(5,2,3) else DashboardPlatformCounts(13,10,3))
        assertThat(requests).hasSize(if(cached)2 else 6)
        assertOverseas(event(requests[failedCalls]))
    }
    @Test fun `MAU 구성 조회 실패는 일부 MAU 합과 그 시각을 응답에 쓰지 않는다`() {
        val now = fixture.clock.instant()
        definitions.activity().forEach { fixture.store.saveDaily(it.definition,mapOf(today to AnalyticsValue(fixture.mapper.valueToTree(0),now))) }
        val one = definitions.mau(today)
        fixture.store.saveRange(one.definition,one.range.from,one.range.to,AnalyticsValue(fixture.mapper.valueToTree(1100),now.minusSeconds(30)))
        val working = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request;return MockResponse().setResponseCode(500)
        } }
        val value = service.activity(range(today,today))
        assertThat(value.monthlyActiveUsers).isNull()
        assertThat(value.asOf).isEqualTo(now)
        assertThat(value.daily).hasSize(1)
        assertThat(requests).hasSize(1)
        fixture.clock.at=fixture.clock.at.plusSeconds(61); server.dispatcher=working
        assertThat(service.activity(range(today,today)).monthlyActiveUsers).isEqualTo(1200)
        assertThat(requests).hasSize(3)
    }

}
