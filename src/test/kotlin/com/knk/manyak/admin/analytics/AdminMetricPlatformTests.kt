package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.config.AdminDashboardProperties
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.JsonNode
import java.time.*
import java.time.format.DateTimeFormatter

/** 신규 가입자, 턴 도달률, 채팅 옵션의 Amplitude 플랫폼 값(결정 35, 36). DB 값은 대역 저장소로 고정한다. */
@ExtendWith(OutputCaptureExtension::class)
class AdminMetricPlatformTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private val now = Instant.parse("2026-10-23T03:00:00Z")
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    /** 이벤트 하나의 하루 값. 필터는 `키` → `연산:첫 값`으로 넘긴다. */
    private var daily: (String, Map<String, String>) -> Long = { _, _ -> 0L }
    private val repository = object : AdminMetricReadRepository(NamedParameterJdbcTemplate(DriverManagerDataSource())) {
        var models = listOf("AURORA", "LITE")
        var reachRows = listOf<Map<String, Any?>>()
        var optionRows = listOf<Map<String, Any?>>()
        override fun signupsBefore(range: AnalyticsRange, excluded: List<Long>) = 0L
        override fun signups(range: AnalyticsRange, excluded: List<Long>) = range.dates().map { mapOf("metric_day" to it.toEpochDay(), "total" to 2L, "google" to 1L, "kakao" to 1L, "migrated" to 0L) }
        override fun reach(range: AnalyticsRange, excluded: List<Long>) = reachRows
        override fun options(range: AnalyticsRange, excluded: List<Long>) = optionRows
        override fun turnsByModel(range: AnalyticsRange, excluded: List<Long>) = emptyList<Map<String, Any?>>()
        override fun modelCodes() = models
    }
    private fun service(f: AnalyticsTestFixture = fixture) = AdminMetricReadService(repository, AdminDashboardProperties(), MetricDefinitions(AnalyticsQueryFactory(f.mapper), f.mapper), f.snapshots, f.clock)
    private fun filters(e: JsonNode) = e["filters"].toList().associate { it["subprop_key"].asText() to it["subprop_op"].asText() + ":" + it["subprop_value"][0].asText() }
    private fun event(r: RecordedRequest) = fixture.mapper.readTree(r.requestUrl!!.queryParameter("e")!!)
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(now))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                val url = r.requestUrl!!; val e = event(r)
                val from = LocalDate.parse(url.queryParameter("start"), DateTimeFormatter.BASIC_ISO_DATE)
                val dates = from.datesUntil(LocalDate.parse(url.queryParameter("end"), DateTimeFormatter.BASIC_ISO_DATE).plusDays(1)).toList()
                val value = daily(e["event_type"].asText(), filters(e))
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() }, "series" to listOf(List(dates.size) { value })))))
            }
        }
    }
    @AfterEach fun stop() = server.shutdown()
    private fun signups(days: Int = 7) = service().signups(com.knk.manyak.admin.dto.AdminChartsRequest().apply { this.days = days.toString() })
    private fun request(from: String, to: String, segment: String? = null) = AdminSegmentRequest().apply { this.from = from; this.to = to; this.segment = segment }
    private fun platform(f: Map<String, String>) = when (f["platform"]) { "is:Web" -> "web"; "is:Android" -> "android"; "is:(none)" -> "unknown"; else -> error("platform 조건 없음 $f") }

    @Test fun `신규 가입자 플랫폼은 가입 이벤트 둘의 국내 세 플랫폼 6회를 더한다`() {
        val values = mapOf(AdminMetricEvents.GOOGLE to mapOf("web" to 3L, "android" to 5L, "unknown" to 1L), AdminMetricEvents.KAKAO to mapOf("web" to 1L, "android" to 2L, "unknown" to 0L))
        daily = { name, f -> values.getValue(name).getValue(platform(f)) }
        val result = signups()
        assertThat(requests).hasSize(6)
        for (r in requests) {
            val f = filters(event(r))
            assertThat(f).containsEntry("is_new_user", "is:true").containsEntry("country", "is:South Korea").doesNotContainKey("user_id")
            assertThat(r.requestUrl!!.queryParameter("s")).isNull(); assertThat(r.requestUrl!!.queryParameter("m")).isEqualTo("uniques")
        }
        assertThat(requests.map { platform(filters(event(it))) to event(it)["event_type"].asText() }).containsExactlyInAnyOrderElementsOf(
            listOf("web", "android", "unknown").flatMap { p -> listOf(p to AdminMetricEvents.GOOGLE, p to AdminMetricEvents.KAKAO) })
        assertThat(result.pendingDates).isEmpty(); assertThat(result.platformAsOf).isNotNull()
        assertThat(result.daily).hasSize(7).allMatch { it.signupsByPlatform == PlatformCounts(12, 4, 7, 1) && it.signups == SignupCounts(2, 1, 1) }
    }

    @Test fun `플랫폼 예산이 3일치만 남으면 DB 값은 7일 모두 주고 나머지 4일은 준비 중이다`() {
        assertThat(fixture.budget.reserve(500 - 18)).isTrue()
        val result = signups()
        assertThat(result.daily).hasSize(7).allMatch { it.signups.total == 2L }
        assertThat(result.daily.takeLast(3)).allMatch { it.signupsByPlatform != null }
        assertThat(result.daily.take(4)).allMatch { it.signupsByPlatform == null }
        assertThat(result.pendingDates).containsExactlyElementsOf(result.daily.take(4).map { it.date })
    }

    @Test fun `대역 500이나 자격 증명이 없어도 DB 값으로 200이고 플랫폼만 준비 중이다`(output: CapturedOutput) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500) }
        val missing = AnalyticsTestFixture(AmplitudeDashboardProperties(), MutableAnalyticsClock(now))
        for (result in listOf(signups(), service(missing).signups(com.knk.manyak.admin.dto.AdminChartsRequest()))) {
            assertThat(result.daily).allMatch { it.signups.total == 2L && it.signupsByPlatform == null }
            assertThat(result.pendingDates).containsExactlyElementsOf(result.daily.map { it.date }); assertThat(result.platformAsOf).isNull()
        }
        assertThat(output.out).contains("admin_analytics_fetch_failed reason=http_error")
    }

    @Test fun `플랫폼 조회가 5초를 넘기면 받은 값만 저장하고 DB 값은 바로 주며 그 키는 쿨다운이다`(output: CapturedOutput) {
        val delegate = server.dispatcher
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = delegate.dispatch(r).setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS) }
        fun timed(call: () -> Unit) { val started = System.nanoTime(); call(); assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofMillis(5800)) }
        val day = com.knk.manyak.admin.dto.AdminChartsRequest().apply { from = "2026-10-23"; to = "2026-10-23" }
        lateinit var signup: SignupResponse
        timed { signup = service().signups(day) }
        assertThat(signup.daily.single().signups.total).isEqualTo(2); assertThat(signup.daily.single().signupsByPlatform).isNull()
        assertThat(signup.pendingDates).containsExactly(LocalDate.parse("2026-10-23"))
        assertThat(output.out).contains("admin_analytics_fetch_failed reason=timeout")
        val stored = MetricDefinitions(AnalyticsQueryFactory(fixture.mapper), fixture.mapper).signupPlatforms().count { fixture.store.daily(it.definition, LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-23")).isNotEmpty() }
        assertThat(stored).isEqualTo(2)
        val sent = server.requestCount
        timed { assertThat(service().signups(day).pendingDates).hasSize(1) }
        assertThat(server.requestCount).isEqualTo(sent) // 60초 쿨다운
        val q = request("2026-10-23", "2026-10-23")
        timed { val r = service().reach(q); assertThat(r.platforms).isNull(); assertThat(r.pendingDates).hasSize(1); assertThat(r.segments).hasSize(3) }
        timed { val r = service().options(q); assertThat(r.daily.single().chatStarts).isNull(); assertThat(r.pendingDates).hasSize(1) }
    }

    @Test fun `턴 도달률 플랫폼은 재생성 아닌 성공 이벤트의 기준 턴 합계 36회다`() {
        service().reach(request("2026-10-17", "2026-10-23"))
        assertThat(requests).hasSize(36)
        for (r in requests) {
            val e = event(r); val f = filters(e)
            assertThat(e["event_type"].asText()).isEqualTo(AdminMetricEvents.CHAT_SUCCEEDED); assertThat(r.requestUrl!!.queryParameter("m")).isEqualTo("totals")
            assertThat(f).containsEntry("is_regenerated", "is:false").containsKeys("country", "platform").doesNotContainKey("user_id")
            assertThat(e["filters"].toList().single { it["subprop_key"].asText() == "turn_number" }["subprop_value"][0].isString).isTrue()
        }
        assertThat(requests.map { filters(event(it))["turn_number"] }.toSet()).containsExactlyInAnyOrderElementsOf(com.knk.manyak.admin.repository.AdminQuery.turnReachThresholds.map { "is:$it" })
        assertThat(service().reach(request("2026-10-17", "2026-10-23")).segment).isEqualTo("all")
    }

    @Test fun `게스트와 회원은 user_id 유무로 거르고 따로 저장하며 다른 값은 400이다`() {
        service().reach(request("2026-10-23", "2026-10-23", "guest"))
        assertThat(requests).hasSize(36).allMatch { filters(event(it))["user_id"] == "is:(none)" }
        requests.clear(); service().reach(request("2026-10-23", "2026-10-23", "member"))
        assertThat(requests).hasSize(36).allMatch { filters(event(it))["user_id"] == "is not:(none)" }
        requests.clear(); service().reach(request("2026-10-23", "2026-10-23"))
        assertThat(requests).hasSize(36)
        assertThatThrownBy { service().reach(request("2026-10-23", "2026-10-23", "visitor")) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { service().options(request("2026-10-23", "2026-10-23", "visitor")) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }

    @Test fun `플랫폼 도달은 준비된 날짜의 합을 1턴 수로 나누고 전체는 세 플랫폼의 합이다`() {
        daily = { _, f -> when (platform(f) to f["turn_number"]) {
            "web" to "is:1" -> 5L; "web" to "is:5" -> 2L; "android" to "is:1" -> 10L; "android" to "is:5" -> 5L; else -> 0L
        } }
        val platforms = service().reach(request("2026-10-22", "2026-10-23")).platforms!!.associateBy { it.platform }
        assertThat(platforms.keys).containsExactly("all", "web", "android", "unknown")
        assertThat(platforms.getValue("web").chats).isEqualTo(10); assertThat(platforms.getValue("web").reached.single { it.turn == 5 }.rate).isEqualTo(0.4)
        assertThat(platforms.getValue("web").reached.single { it.turn == 50 }.chats).isZero()
        assertThat(platforms.getValue("all").chats).isEqualTo(30); assertThat(platforms.getValue("all").reached.single { it.turn == 5 }.chats).isEqualTo(14)
        assertThat(platforms.getValue("unknown").chats).isZero(); assertThat(platforms.getValue("unknown").reached).allMatch { it.rate == 0.0 }
        assertThat(platforms.values).allMatch { p -> p.reached.map { it.turn } == com.knk.manyak.admin.repository.AdminQuery.turnReachThresholds }
    }

    @Test fun `범위 전에 시작한 채팅이 있으면 플랫폼 도달 비율은 1을 넘는다`() {
        daily = { _, f -> if (platform(f) != "web") 0L else when (f["turn_number"]) { "is:1" -> 2L; "is:2" -> 3L; else -> 0L } }
        assertThat(service().reach(request("2026-10-23", "2026-10-23")).platforms!!.first { it.platform == "web" }.reached.single { it.turn == 2 }.rate).isEqualTo(1.5)
    }

    @Test fun `segment는 플랫폼 값만 거르고 대역 장애에도 DB 도달률은 그대로다`() {
        repository.reachRows = listOf(mapOf("segment" to "guest", "chats" to 1L, "turn_1" to 1L), mapOf("segment" to "member", "chats" to 1L, "turn_1" to 0L))
        val all = service().reach(request("2026-10-23", "2026-10-23"))
        val member = service().reach(request("2026-10-23", "2026-10-23", "member"))
        assertThat(member.segments).isEqualTo(all.segments); assertThat(member.segments.map { it.chats }).containsExactly(2L, 1L, 1L)
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500) }
        val failed = service().reach(request("2026-10-22", "2026-10-22", "member"))
        assertThat(failed.platforms).isNull(); assertThat(failed.platformAsOf).isNull(); assertThat(failed.pendingDates).containsExactly(LocalDate.parse("2026-10-22"))
        assertThat(failed.segments).isEqualTo(all.segments); assertThat(failed.guestToMember).isEqualTo(all.guestToMember)
    }

    @Test fun `채팅 옵션 플랫폼은 시작 클릭 6회와 모델마다 세 플랫폼 턴 수를 보낸다`() {
        val clicks = mapOf("web" to mapOf("default" to 8L, "persona" to 2L), "android" to mapOf("default" to 5L, "persona" to 0L))
        val turns = mapOf("web" to mapOf("LITE" to 4L, "AURORA" to 1L), "android" to mapOf("LITE" to 3L, "AURORA" to 0L))
        daily = { name, f -> if (name == AdminMetricEvents.CHAT_START) clicks[platform(f)]?.get(f.getValue("persona_type").removePrefix("is:")) ?: 0L
            else turns[platform(f)]?.get(f.getValue("model_code").removePrefix("is:")) ?: 0L }
        val day = service().options(request("2026-10-23", "2026-10-23")).daily.single()
        assertThat(requests).hasSize(12)
        val starts = requests.filter { event(it)["event_type"].asText() == AdminMetricEvents.CHAT_START }
        assertThat(starts.map { platform(filters(event(it))) to filters(event(it))["persona_type"] }).containsExactlyInAnyOrderElementsOf(
            listOf("web", "android", "unknown").flatMap { p -> listOf(p to "is:default", p to "is:persona") })
        val models = requests - starts.toSet()
        assertThat(models).allMatch { event(it)["event_type"].asText() == AdminMetricEvents.CHAT_SUCCEEDED && filters(event(it))["is_regenerated"] == "is:false" }
        assertThat(models.map { platform(filters(event(it))) to filters(event(it))["model_code"] }).containsExactlyInAnyOrderElementsOf(
            listOf("web", "android", "unknown").flatMap { p -> listOf(p to "is:LITE", p to "is:AURORA") })
        assertThat(requests).allMatch { it.requestUrl!!.queryParameter("m") == "totals" && !filters(event(it)).containsKey("user_id") }
        assertThat(day.chatStarts).isEqualTo(PlatformCounts(15, 10, 5, 0)); assertThat(day.personaChatStarts).isEqualTo(PlatformCounts(2, 2, 0, 0))
        assertThat(day.platformTurnsByModel).isEqualTo(mapOf("AURORA" to PlatformCounts(1, 1, 0, 0), "LITE" to PlatformCounts(7, 4, 3, 0)))
        repository.models = listOf("AURORA", "LITE", "NOVA"); requests.clear()
        val added = service().options(request("2026-10-23", "2026-10-23")).daily.single()
        assertThat(requests).hasSize(3).allMatch { filters(event(it))["model_code"] == "is:NOVA" }
        assertThat(added.platformTurnsByModel!!.getValue("NOVA")).isEqualTo(PlatformCounts(0, 0, 0, 0))
    }

    @Test fun `채팅 옵션 대역 장애는 DB 값만 주고 플랫폼 값 셋은 null이다`() {
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(500) }
        repository.optionRows = listOf(mapOf("metric_day" to LocalDate.parse("2026-10-23").toEpochDay(), "model" to "LITE", "chats" to 3L, "persona" to 1L))
        val result = service().options(request("2026-10-22", "2026-10-23"))
        assertThat(result.pendingDates).hasSize(2); assertThat(result.platformAsOf).isNull()
        assertThat(result.daily.last().chats).isEqualTo(3); assertThat(result.daily.last().withPersona).isEqualTo(1); assertThat(result.daily.last().byModel).isEqualTo(mapOf("LITE" to 3L))
        assertThat(result.daily).allMatch { it.chatStarts == null && it.personaChatStarts == null && it.platformTurnsByModel == null }
    }
}
