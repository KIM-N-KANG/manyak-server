package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminDateRange
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.time.format.DateTimeFormatter

class AdminMetricsTests {
    private val mapper = ObjectMapper()
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var service: AdminMetricsService
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private var broken = false
    private var retentionValues: Map<LocalDate, List<Map<String, Any>>>? = null
    private var retentionBySe: ((String, LocalDate) -> List<Map<String, Any>>?)? = null
    private var lengthValues: Map<LocalDate, List<Int>> = emptyMap()
    private var lengthXValues = listOf("0s-3s", "3s-10s", "10s-30s", "30s-60s", "60s-180s", "180s-600s", "600s-1800s", "1800s-3600s", "3600s-86400s")
    private var lengthResponse: String? = null
    private var lengthBroken = false
    @BeforeEach fun setup() {
        server = MockWebServer(); server.start()
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z")))
        service = AdminMetricsService(MetricDefinitions(AnalyticsQueryFactory(mapper), mapper), fixture.snapshots, AnalyticsQueryFactory(mapper), modelCodeReads(), com.knk.manyak.admin.config.AdminDashboardProperties(), fixture.clock)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests += r
                if (broken) return MockResponse().setResponseCode(500)
                val url = r.requestUrl!!; val from = LocalDate.parse(url.queryParameter("start"), DateTimeFormatter.BASIC_ISO_DATE); val to = LocalDate.parse(url.queryParameter("end"), DateTimeFormatter.BASIC_ISO_DATE)
                val range = AnalyticsRange(from, to); val path = url.encodedPath
                val data: Any = when {
                    path.endsWith("/retention") -> mapOf("series" to listOf(mapOf("values" to range.dates().associate { date -> date.format(DateTimeFormatter.ofPattern("MMM d, uuuu", java.util.Locale.ENGLISH)) to (retentionBySe?.invoke(url.queryParameter("se")!!, date) ?: retentionValues?.get(date) ?: List(32) { i -> mapOf("count" to if (i==0) 20 else if (date.dayOfMonth==19) 5 else 10, "outof" to 20, "incomplete" to (i==8)) }) })))
                    path.endsWith("/funnels") -> { val events = url.queryParameterValues("e"); val kakao = events.last()!!.contains(AdminMetricEvents.KAKAO); listOf(mapOf("cumulativeRaw" to if (events.size==3) listOf(10,6,if (kakao) 2 else 5) else List(events.size) { 20-it })) }
                    path.endsWith("/users") -> mapOf("series" to listOf(listOf(73)), "xValues" to listOf(from.toString()))
                    path.endsWith("/sessions/length") -> if (lengthBroken) return MockResponse().setResponseCode(500)
                        else lengthResponse?.let { mapper.readTree(it)["data"] } ?: mapOf("series" to listOf(lengthValues[from] ?: List(9) { 1 }), "xValues" to lengthXValues)
                    else -> {
                        val event = url.queryParameter("e")?.let { mapper.readTree(it) }; val filters = event?.get("filters")?.toList().orEmpty()
                        val platformFilter = filters.firstOrNull { it["subprop_key"].asText()=="platform" }
                        val platform = if (platformFilter?.get("subprop_op")?.asText()=="is not") "nonAndroid" else platformFilter?.get("subprop_value")?.get(0)?.asText()
                        val name = event?.get("event_type")?.asText()
                        val value: Number = when {
                            path.endsWith("/average") -> 123.75
                            url.queryParameter("m")=="average" -> 4.5
                            name=="_active" -> if(filters.any { it["subprop_key"].asText()=="user_id" }) 1 else when(platform) { "Web" -> 10; "Android" -> 2; "(none)" -> 2; "nonAndroid" -> 11; else -> 13 }
                            name==AdminMetricEvents.MESSAGE -> if (filters.any { it["subprop_key"].asText()=="input_mode" }) 0 else 2
                            name=="client_chat_streamError_shown" -> 3
                            else -> 1
                        }
                        mapOf("xValues" to range.dates().map { if (path.endsWith("/average")) it.toString()+"T00:00:00" else it.toString() }, "series" to listOf(List(range.days) { value }), "seriesCollapsed" to listOf(listOf(mapOf("value" to 73))))
                    }
                }
                return MockResponse().setBody(mapper.writeValueAsString(mapOf("data" to data)))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun range(from: String, to: String) = SegmentRangeQuery().apply { this.from=from; this.to=to }
    @Test fun `활동은 전체와 플랫폼을 따로 조회하고 MAU를 우선 예약한다`() {
        val value = service.activity(range("2026-10-15", "2026-10-21"))
        assertThat(value.monthlyActiveUsers).isEqualTo(219)
        assertThat(value.daily.first().activeUsers).isEqualTo(PlatformCounts(14,10,3,2))
        assertThat(value.daily.first().messagesPerUser).isEqualTo(PlatformDecimals(null,4.5,4.5))
        assertThat(value.daily.last().cumulativeNewVisitors).isEqualTo(PlatformCounts(14,7,7,7))
        assertThat(requests).hasSize(14)
        val first = requests.first().requestUrl!!
        assertThat(first.encodedPath).isEqualTo("/api/2/events/segmentation"); assertThat(first.queryParameter("start")).isEqualTo("20260922"); assertThat(first.queryParameter("end")).isEqualTo("20261021")
        assertThat(first.queryParameter("s")).isNull(); assertThat(first.queryParameter("e")).contains("_active", "country"); assertThat(first.queryParameter("i")).isEqualTo("1")
        requests.drop(3).forEach { assertThat(it.requestUrl!!.queryParameter("s")).isNull(); assertThat(it.requestUrl!!.queryParameter("g")).isNull() }
        assertThat(fixture.budget.reserve(333)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        val count=requests.size; service.activity(range("2026-10-15","2026-10-21")); assertThat(requests).hasSize(count)
    }
    @Test fun `활동 366일은 MAU 90을 먼저 받고 최근 37일을 준비한다`() {
        val value = service.activity(range("2025-10-23","2026-10-23"))
        assertThat(value.daily).hasSize(37); assertThat(value.pendingDates).hasSize(329); assertThat(value.monthlyActiveUsers).isEqualTo(219)
        assertThat(fixture.budget.reserve(3)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        assertThatThrownBy { service.activity(range("2025-10-22","2026-10-23")) }.hasMessageContaining("기간")
    }
    @Test fun `리텐션은 인덱스2와 가중 평균을 쓰고 incomplete와 미종료일은 null이다`() {
        val value = service.retention(range("2026-10-19","2026-10-22"))
        assertThat(value.platforms.map { it.platform }).containsExactly("all","web","android")
        val all=value.platforms.first(); assertThat(all.cohorts.first().d1).isEqualTo(0.25); assertThat(all.cohorts.last().d1).isNull()
        assertThat(all.cohorts.first().d7).isNull(); assertThat(all.cohorts.first().d30).isNull()
        assertThat(all.summary.d1).isEqualTo(25.0/60)
        assertThat(requests).hasSize(3)
        requests.forEach { val u=it.requestUrl!!; assertThat(u.queryParameter("rm")).isNull(); assertThat(u.queryParameter("s")).isNull(); assertThat(u.queryParameter("se")).contains("_new","country"); assertThat(u.queryParameter("re")).contains("_active","country") }
        assertThat(fixture.budget.reserve(404)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        service.retention(range("2026-10-19","2026-10-22")); assertThat(requests).hasSize(3)
        assertThatThrownBy { service.retention(AnalyticsRangeQuery().apply { days="30" }) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { service.retention(range("2026-10-22","2026-10-23")) }.isInstanceOf(RuntimeException::class.java)
    }
    @Test fun `리텐션 인덱스 2부터 8과 31을 순서대로 응답하고 분모 0과 미완료 및 없는 인덱스는 null이다`() {
        val date = fixture.clock.instant().atZone(AdminDateRange.KST).toLocalDate().minusDays(20)
        retentionValues = mapOf(date to List(11) { day ->
            mapOf("count" to when (day) { 0 -> 20; 1 -> 19; in 2..6 -> 7 - day; else -> 0 },
                "outof" to if (day == 7) 0 else 20, "incomplete" to (day == 8))
        })
        val value = mapper.readTree(mapper.writeValueAsString(service.retention(range(date.toString(), date.toString()))))
        val fields = listOf("d1", "d2", "d3", "d4", "d5", "d6", "d7", "d30")
        for (platform in value["platforms"]) {
            val cohort = platform["cohorts"][0]
            assertThat(cohort["users"].asLong()).isEqualTo(20)
            assertThat(cohort.properties().map { it.key }).containsExactlyElementsOf(listOf("date", "users") + fields)
            assertThat(platform["summary"].properties().map { it.key }).containsExactlyElementsOf(fields)
            for (body in listOf(cohort, platform["summary"])) {
                for ((day, expected) in listOf(0.25, 0.2, 0.15, 0.1, 0.05).withIndex()) {
                    assertThat(body["d${day + 1}"].asDouble()).isEqualTo(expected)
                }
                for (field in listOf("d6", "d7", "d30")) assertThat(body[field].isNull).describedAs(field).isTrue()
            }
        }
        assertThat(requests).hasSize(3)
    }

    @Test fun `D1은 Day 0 인덱스1이 아니라 인덱스2이며 짧은 배열 경계도 null이다`() {
        val date = fixture.clock.instant().atZone(AdminDateRange.KST).toLocalDate().minusDays(10)
        retentionValues = mapOf(date to listOf(200, 199, 30).map { mapOf("count" to it, "outof" to 200, "incomplete" to false) })
        val value = service.retention(range(date.toString(), date.toString()))
        assertThat(mapper.writeValueAsString(value)).doesNotContain("0.995")
        for (platform in value.platforms) {
            assertThat(platform.cohorts.single().users).isEqualTo(200)
            assertThat(platform.cohorts.single().d1).isEqualTo(0.15)
            assertThat(platform.cohorts.single().d2).isNull()
            assertThat(platform.summary.d1).isEqualTo(0.15)
            assertThat(platform.summary.d2).isNull()
        }
    }

    @Test fun `완료된 코호트의 D7과 D30은 인덱스8과31을 읽는다`() {
        val date = fixture.clock.instant().atZone(AdminDateRange.KST).toLocalDate().minusDays(40)
        retentionValues = mapOf(date to List(32) { i -> mapOf("count" to if (i == 0) 200 else i, "outof" to 200, "incomplete" to false) })
        for (platform in service.retention(range(date.toString(), date.toString())).platforms) {
            assertThat(platform.cohorts.single().d7).isEqualTo(8.0 / 200)
            assertThat(platform.cohorts.single().d30).isEqualTo(31.0 / 200)
            assertThat(platform.summary.d30).isEqualTo(31.0 / 200)
        }
    }

    @Test fun `리텐션 D3 요약은 관찰 날짜가 끝난 코호트만 분모로 가중한다`() {
        fixture.clock.at = Instant.parse("2026-10-26T03:00:00Z")
        retentionValues = (21..23).associate { day ->
            LocalDate.of(2026, 10, day) to List(32) { index ->
                mapOf("count" to if (index == 0) when (day) { 21 -> 10; 22 -> 30; else -> 5 } else if (index == 4) when (day) { 21 -> 1; 22 -> 9; else -> 5 } else 0,
                    "outof" to when (day) { 21 -> 10; 22 -> 30; else -> 5 }, "incomplete" to false)
            }
        }
        val value = mapper.readTree(mapper.writeValueAsString(service.retention(range("2026-10-21", "2026-10-23"))))
        for (platform in value["platforms"]) {
            assertThat(platform["cohorts"][0]["d3"].asDouble()).isEqualTo(0.1)
            assertThat(platform["cohorts"][1]["d3"].asDouble()).isEqualTo(0.3)
            assertThat(platform["cohorts"][2]["d3"].isNull).isTrue()
            assertThat(platform["summary"]["d3"].asDouble()).isEqualTo(0.25)
            assertThat(platform["summary"]["d6"].isNull).isTrue()
        }
        assertThat(requests).hasSize(3)
        service.retention(range("2026-10-21", "2026-10-23"))
        assertThat(requests).hasSize(3)
    }
    @Test fun `세션은 평균 길이만 세 조건으로 받고 저장된 날은 다시 받지 않는다`() {
        val value=service.sessions(SegmentRangeQuery())
        assertThat(value.daily).hasSize(14); assertThat(value.daily.first().averageSessionSeconds).isEqualTo(PlatformDecimals(123.75,123.75,123.75))
        assertThat(requests).hasSize(3); requests.forEach { assertThat(it.requestUrl!!.encodedPath).isEqualTo("/api/2/sessions/average"); assertThat(it.requestUrl!!.queryParameter("s")).contains("country") }
        assertThat(mapper.readTree(requests[0].requestUrl!!.queryParameter("s")!!).size()).isEqualTo(1)
        assertThat(mapper.readTree(requests[1].requestUrl!!.queryParameter("s")!!).size()).isEqualTo(2)
        assertThat(fixture.budget.reserve(52)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `세션 segment는 세그먼트 조건에 user_id를 더해 따로 저장하고 하루 44를 쓴다`() {
        val q=range("2026-10-17","2026-10-23").let { r -> SegmentRangeQuery().apply { from=r.from; to=r.to; segment="member" } }
        val member=service.sessions(q)
        assertThat(member.segment).isEqualTo("member"); assertThat(requests).hasSize(3)
        requests.forEach { assertThat(mapper.readTree(it.requestUrl!!.queryParameter("s")!!).toList().last().toString()).isEqualTo("{\"prop\":\"user_id\",\"op\":\"is not\",\"values\":[\"(none)\"]}") }
        assertThat(fixture.budget.reserve(500-308)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        fixture.clock.at=fixture.clock.at.plusSeconds(300); requests.clear()
        service.sessions(SegmentRangeQuery().apply { from=q.from; to=q.to; segment="guest" })
        assertThat(requests).hasSize(3).allMatch { mapper.readTree(it.requestUrl!!.queryParameter("s")!!).toList().last()["op"].asText()=="is" }
        requests.clear(); assertThat(service.sessions(SegmentRangeQuery().apply { from=q.from; to=q.to }).segment).isEqualTo("all")
        assertThat(requests).hasSize(3).allMatch { !it.requestUrl!!.queryParameter("s")!!.contains("user_id") }
        assertThatThrownBy { service.sessions(SegmentRangeQuery().apply { segment="visitor" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `기능 사용 segment는 user_id 필터를 더하고 게스트는 앱 해외 로그인 묶음을 0으로 둔다`() {
        fun q(segment: String?) = PlatformMetricsQuery().apply { from="2026-10-23"; to="2026-10-23"; platform="all"; this.segment=segment }
        val all=service.quality(q(null)); assertThat(all.segment).isEqualTo("all"); assertThat(requests).hasSize(33)
        requests.clear(); val guest=service.quality(q("guest"))
        assertThat(guest.segment).isEqualTo("guest"); assertThat(requests).hasSize(22)
        assertThat(requests).allMatch { val f=mapper.readTree(it.requestUrl!!.queryParameter("e")!!)["filters"].toList().filter { x -> x["subprop_key"].asText()=="user_id" }
            f.size==1 && f[0]["subprop_op"].asText()=="is" && f[0]["subprop_value"][0].asText()=="(none)" }
        assertThat(guest.daily.single().messagesByInputMode["unknown"]).isEqualTo(4); assertThat(all.daily.single().messagesByInputMode["unknown"]).isEqualTo(6)
        requests.clear(); val member=service.quality(q("member"))
        assertThat(requests).hasSize(22).allMatch { mapper.readTree(it.requestUrl!!.queryParameter("e")!!)["filters"].toList().single { x -> x["subprop_key"].asText()=="user_id" }["subprop_op"].asText()=="is not" }
        assertThat(member.daily.single().messagesByInputMode["unknown"]).isEqualTo(6)
        assertThatThrownBy { service.quality(q("visitor")) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `세션 60일은 최근15일 뒤 5분후 다음15일을 받는다`() {
        val q=range("2026-08-25","2026-10-23")
        val a=service.sessions(q); assertThat(a.daily).hasSize(15); assertThat(a.pendingDates).hasSize(45)
        val count=requests.size; service.sessions(q); assertThat(requests).hasSize(count)
        fixture.clock.at=fixture.clock.at.plusSeconds(300)
        val b=service.sessions(q); assertThat(b.daily).hasSize(30); assertThat(b.pendingDates).hasSize(30)
    }
    @Test fun `기능 지표는 입력방식을 따로 받고 단순 오류율 1초과를 보존한다`() {
        val q=PlatformMetricsQuery().apply { from="2026-10-20"; to="2026-10-21"; platform="web" }
        val value=service.quality(q)
        assertThat(value.daily.first().streamErrorRate).isEqualTo(1.5); assertThat(value.daily.first().messagesByInputMode["unknown"]).isEqualTo(2)
        assertThat(value.daily.first().users.keys).containsExactly("chatLoadError","storyListLoadError","creditShortage","creditChargeViewed","attendanceClicked","inviteSucceeded")
        assertThat(requests).hasSize(11)
        assertThat(requests).noneMatch { it.requestUrl!!.queryParameter("e")!!.contains("client_storyCreate_completeError_shown") }
        requests.forEach { val u=it.requestUrl!!; assertThat(u.queryParameter("e")).contains("country","platform","Web"); assertThat(u.queryParameter("s")).isNull(); assertThat(u.queryParameter("g")).isNull() }
        assertThat(requests.count { it.requestUrl!!.queryParameter("e")!!.contains("input_mode") }).isEqualTo(3)
        assertThatThrownBy { service.quality(PlatformMetricsQuery().apply { platform="unknown" }) }.isInstanceOf(RuntimeException::class.java)
    }
    @Test fun `퍼널의 플랫폼 스토리와 턴 조건 및 중복 로그인 합을 보존한다`() {
        val q=ConversionMetricsQuery().apply { from="2026-10-21"; to="2026-10-21" }
        val login=service.funnel("guest-limit-login",q)
        assertThat(login.steps[1].users).isEqualTo(6); assertThat(login.steps[2].users).isEqualTo(7); assertThat(login.steps[2].event).isEqualTo("login_succeeded")
        val id=java.util.UUID.randomUUID(); q.storyId=id; q.platform="android"
        val story=service.funnel("story-detail-chat",q)
        assertThat(story.steps.last().turnNumber).isEqualTo(5)
        val events=requests.last().requestUrl!!.queryParameterValues("e").map { mapper.readTree(it!!) }
        assertThat(events[0].toString()).contains("story_id",id.toString(),"Android"); assertThat(events[1].toString()).contains("story_id")
        assertThat(events[6].toString()).contains("turn_number","5").doesNotContain("campaign")
        assertThatThrownBy { service.funnel("web-first-visit",q) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { service.funnel("unknown",ConversionMetricsQuery()) }.isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { service.funnel("story-creation",q) }.isInstanceOf(RuntimeException::class.java)
    }
    @Test fun `새 세션 조회 실패는 저장소가 없으면 사용 불가이고 비용은 반환된다`() {
        broken=true
        assertThatThrownBy { service.sessions(SegmentRangeQuery()) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(requests).hasSize(1); service.runCatching { sessions(SegmentRangeQuery()) }; assertThat(requests).hasSize(1)
        assertThat(fixture.budget.reserve(500)).isTrue()
    }
    @Test fun `서로 다른 지표도 공용 예산을 나눠 쓰고 준비된 날짜만 반환한다`() {
        service.activity(range("2026-10-15","2026-10-21")) // MAU 90 + 일별 77
        val result=service.quality(PlatformMetricsQuery().apply { from="2026-09-21"; to="2026-10-21" })
        assertThat(result.daily).hasSize(10); assertThat(result.pendingDates).hasSize(21)
        assertThat(fixture.budget.reserve(3)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `MAU 실패도 저장된 일별 활동은 반환하고 MAU만 null이다`() {
        val date=LocalDate.parse("2026-10-21")
        val definitions=MetricDefinitions(AnalyticsQueryFactory(mapper),mapper)
        definitions.activity().forEach { fixture.store.saveDaily(it.definition,mapOf(date to AnalyticsValue(mapper.valueToTree(2),fixture.clock.instant(),fixture.clock.instant()))) }
        broken=true
        val result=service.activity(range(date.toString(),date.toString()))
        assertThat(result.daily).hasSize(1); assertThat(result.monthlyActiveUsers).isNull(); assertThat(result.pendingDates).isEmpty()
        assertThat(requests).hasSize(1)
    }

    @Test fun `리텐션과 기능 지표의 366일 첫 조회도 비용 안에서 부분 응답한다`() {
        val retention=service.retention(range("2025-10-22","2026-10-22"))
        assertThat(retention.platforms.first().cohorts).hasSize(20); assertThat(retention.pendingDates).hasSize(346)
        fixture.clock.at=fixture.clock.at.plusSeconds(300)
        val quality=service.quality(PlatformMetricsQuery().apply { from="2025-10-23"; to="2026-10-23" })
        assertThat(quality.daily).hasSize(15); assertThat(quality.pendingDates).hasSize(351)
        assertThat(fixture.budget.reserve(5)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }

    private fun days7() = AnalyticsRangeQuery().apply { days = "7" }
    private fun point(count: Int, outof: Int, incomplete: Boolean = false) = mapOf<String, Any>("count" to count, "outof" to outof, "incomplete" to incomplete)

    @Test fun `리텐션 가입자는 플랫폼마다 구글과 카카오 로그인 성공을 시작 이벤트로 6회 조회한다`() {
        assertThat(service.retention(days7(), "signup").cohort).isEqualTo("signup")
        assertThat(requests).hasSize(6)
        val starts = requests.map { mapper.readTree(it.requestUrl!!.queryParameter("se")!!) }
        assertThat(starts.map { it["event_type"].asText() }).containsExactly(AdminMetricEvents.GOOGLE, AdminMetricEvents.KAKAO, AdminMetricEvents.GOOGLE, AdminMetricEvents.KAKAO, AdminMetricEvents.GOOGLE, AdminMetricEvents.KAKAO)
        assertThat(starts).allMatch { it["filters"].any { f -> f["subprop_key"].asText() == "is_new_user" && f["subprop_value"][0].asText() == "true" } && it["filters"].toString().contains("country") }
        assertThat(requests).allMatch { mapper.readTree(it.requestUrl!!.queryParameter("re")!!)["event_type"].asText() == "_active" }
        assertThat(fixture.budget.reserve(500 - 336)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `가입자는 같은 플랫폼 두 배열을 인덱스마다 더하고 하나라도 incomplete면 null이다`() {
        val date = fixture.clock.instant().atZone(AdminDateRange.KST).toLocalDate().minusDays(20)
        retentionBySe = { se, _ -> if (se.contains(AdminMetricEvents.GOOGLE)) listOf(point(3, 3), point(3, 3), point(1, 3), point(1, 3))
            else listOf(point(2, 2), point(2, 2), point(1, 2), point(0, 2, true)) }
        for (platform in service.retention(range(date.toString(), date.toString()), "signup").platforms) {
            val row = platform.cohorts.single()
            assertThat(row.users).isEqualTo(5); assertThat(row.d1).isEqualTo(0.4); assertThat(row.d2).isNull()
        }
    }
    @Test fun `리텐션 채팅 시작자는 턴 1 재생성 아닌 AI 응답 성공을 시작 이벤트로 3회 조회한다`() {
        assertThat(service.retention(days7(), "first-chat").cohort).isEqualTo("first-chat")
        assertThat(requests).hasSize(3)
        requests.forEach {
            val start = mapper.readTree(it.requestUrl!!.queryParameter("se")!!)
            assertThat(start["event_type"].asText()).isEqualTo(AdminMetricEvents.CHAT_SUCCEEDED)
            val filters = start["filters"].associate { f -> f["subprop_key"].asText() to f["subprop_value"][0].asText() }
            assertThat(filters).containsEntry("turn_number", "1").containsEntry("is_regenerated", "false")
            assertThat(mapper.readTree(it.requestUrl!!.queryParameter("re")!!)["event_type"].asText()).isEqualTo("_active")
        }
        assertThat(fixture.budget.reserve(500 - 168)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }
    @Test fun `시작 집단마다 저장 키가 따로이고 방문자 키는 cohort 생략과 같다`() {
        assertThat(service.retention(days7()).cohort).isEqualTo("visitor"); assertThat(requests).hasSize(3)
        // 방문자 정의 해시가 바뀌면 배포 전 저장된 코호트를 처음부터 다시 받는다.
        assertThat(MetricDefinitions(AnalyticsQueryFactory(mapper), mapper).retention().map { it.definition.hash }).containsExactly(
            "eba32706edb42ed235ecc29b28ecfbe3aa4ee32ee7af2c25bdd068c093029947", "4ea844084c3d7effc6f985b39c3fdb8170d6fb3d2db357f2893d1aef5f82d260", "4fab200ba533d29409029ed5e174bb1022465555027a71985567bf21a142c858")
        assertThat(service.retention(days7(), "visitor").cohort).isEqualTo("visitor"); assertThat(requests).hasSize(3)
        fixture.clock.at = fixture.clock.at.plusSeconds(300)
        service.retention(days7(), "first-chat"); assertThat(requests).hasSize(6)
        assertThatThrownBy { service.retention(days7(), "member") }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `세션 길이 분포는 날짜마다 세 조건을 따로 조회하고 평균은 부르지 않는다`() {
        val value = service.sessionLengths(SegmentRangeQuery().apply { days = "7" })
        assertThat(value.segment).isEqualTo("all")
        assertThat(requests).hasSize(21).allMatch { it.requestUrl!!.encodedPath == "/api/2/sessions/length" }
        requests.forEach { val u = it.requestUrl!!
            assertThat(u.queryParameter("start")).isEqualTo(u.queryParameter("end"))
            assertThat(u.queryParameterNames).containsExactlyInAnyOrder("s", "start", "end")
        }
        val averages = MetricDefinitions(AnalyticsQueryFactory(mapper), mapper).sessions().map { it.definition.parameters.toMap()["s"] }
        assertThat(requests.map { it.requestUrl!!.queryParameter("s") }.distinct()).containsExactlyInAnyOrderElementsOf(averages)
        assertThat(fixture.budget.reserve(500 - 224)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        assertThat(value.bins.map { it.fromSeconds }).containsExactly(0, 3, 10, 30, 60, 180, 600, 1800, 3600)
        assertThat(value.bins.map { it.toSeconds }).containsExactly(3, 10, 30, 60, 180, 600, 1800, 3600, 86400)
        assertThat(value.platforms.map { it.platform }).containsExactly("all", "web", "android")
    }
    @Test fun `세션 길이는 날짜별 구간 합과 중앙값 구간을 응답한다`() {
        val d = LocalDate.parse("2026-10-17")
        fun bins(vararg values: Int) = values.toList() + List(9 - values.size) { 0 }
        lengthValues = mapOf(d to bins(2, 1, 1), d.plusDays(1) to bins(1, 1, 2, 1), d.plusDays(2) to bins(5, 5), d.plusDays(3) to bins())
        fun all(from: LocalDate, to: LocalDate) = service.sessionLengths(SegmentRangeQuery().apply { this.from = from.toString(); this.to = to.toString() }).platforms.first()
        val two = all(d, d.plusDays(1))
        assertThat(two.sessions).containsExactly(3, 2, 3, 1, 0, 0, 0, 0, 0); assertThat(two.totalSessions).isEqualTo(9); assertThat(two.medianBin).isEqualTo(1)
        assertThat(all(d.plusDays(2), d.plusDays(2)).medianBin).isEqualTo(0)
        val empty = all(d.plusDays(3), d.plusDays(3)); assertThat(empty.totalSessions).isZero(); assertThat(empty.medianBin).isNull()
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = ["full", "sparse", "empty"])
    fun `세션 길이 실측 응답은 누락 구간을 0으로 저장하고 준비된 날짜로 응답한다`(shape: String) {
        lengthResponse = javaClass.getResource("/amplitude/session-length-$shape.json")!!.readText()
        val expected = when (shape) { "full" -> listOf(105L,35,39,14,34,31,16,4,2); "sparse" -> listOf(8L,8,4,4,7,4,1,0,0); else -> List(9) { 0L } }
        val date = LocalDate.parse("2026-10-20")
        val value = service.sessionLengths(range(date.toString(), date.toString()))
        assertThat(value.pendingDates).isEmpty()
        value.platforms.forEach { platform ->
            assertThat(platform.sessions).isEqualTo(expected); assertThat(platform.totalSessions).isEqualTo(expected.sum())
            assertThat(platform.medianBin).isEqualTo(if (shape == "empty") null else if (shape == "full") 1 else 2)
        }
        MetricDefinitions(AnalyticsQueryFactory(mapper), mapper).sessionLengths().forEach {
            assertThat(fixture.store.daily(it.definition, date, date)[date]!!.value.toList().map { it.asLong() }).isEqualTo(expected)
        }
    }
    @Test fun `세션 길이는 구간 이름으로 순서를 맞추고 중간 누락을 0으로 채운다`() {
        lengthXValues = listOf("600s-1800s", "3s-10s")
        lengthValues = mapOf(LocalDate.parse("2026-10-20") to listOf(4, 2))
        val value = service.sessionLengths(range("2026-10-20", "2026-10-20"))
        assertThat(value.platforms.first().sessions).containsExactly(0,2,0,0,0,0,4,0,0)
    }
    @Test fun `세션 길이의 잘못된 경계 중복 길이와 값은 형식 오류로 저장하지 않는다`() {
        val invalid = listOf(
            listOf("0s-4s") to listOf(1), listOf("0s-3s", "0s-3s") to listOf(1,2),
            listOf("0s-3s", "3s-10s") to listOf(1), (lengthXValues + "86400s-90000s") to List(10) { 1 },
            listOf("0 - 3000") to listOf(1), listOf("0s-3s") to listOf(-1), listOf("0s-3s") to listOf(1.5),
            listOf("0s-3s") to listOf("1"), emptyList<String>() to listOf(1))
        val date = LocalDate.parse("2026-10-20"); val defs = MetricDefinitions(AnalyticsQueryFactory(mapper), mapper)
        for ((labels, values) in invalid) {
            val data = mapper.valueToTree<tools.jackson.databind.JsonNode>(mapOf("xValues" to labels, "series" to listOf(values)))
            assertThatThrownBy { defs.sessionLengths().first().decode(data, AnalyticsRange(date, date)) }
                .isInstanceOfSatisfying(AmplitudeFetchFailure::class.java) { assertThat(it.reason).isEqualTo(AnalyticsFailureReason.parse_error) }
            lengthResponse = mapper.writeValueAsString(mapOf("data" to data)); requests.clear(); fixture.clock.at = fixture.clock.at.plusSeconds(300)
            assertThatThrownBy { service.sessionLengths(range(date.toString(), date.toString())) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
            assertThat(requests).hasSize(1)
            defs.sessionLengths().forEach { assertThat(fixture.store.daily(it.definition, date, date)).isEmpty() }
        }
    }
    @Test fun `세션 길이 조회 실패는 평균 세션 길이와 쿨다운을 공유하지 않는다`() {
        lengthBroken = true
        assertThatThrownBy { service.sessionLengths(SegmentRangeQuery().apply { days = "7" }) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        requests.clear()
        assertThat(service.sessions(SegmentRangeQuery().apply { days = "7" }).daily).hasSize(7)
        assertThat(requests).hasSize(3).allMatch { it.requestUrl!!.encodedPath == "/api/2/sessions/average" }
    }
    @Test fun `세션 길이 회원은 세그먼트에 user_id 조건을 더해 따로 저장한다`() {
        service.sessionLengths(SegmentRangeQuery().apply { days = "7"; segment = "member" })
        assertThat(requests).hasSize(21).allMatch { mapper.readTree(it.requestUrl!!.queryParameter("s")!!).toList().last().toString() == "{\"prop\":\"user_id\",\"op\":\"is not\",\"values\":[\"(none)\"]}" }
        assertThat(fixture.budget.reserve(500 - 308)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
        val defs = MetricDefinitions(AnalyticsQueryFactory(mapper), mapper)
        assertThat(defs.sessionLengths("member").map { it.definition.hash }).doesNotContainAnyElementsOf(defs.sessionLengths().map { it.definition.hash })
        assertThatThrownBy { service.sessionLengths(SegmentRangeQuery().apply { segment = "visitor" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `웹 첫 접속 퍼널은 홈 진입과 간편 제작 진입으로 시작하는 신규 사용자 기준이고 다른 퍼널은 활성 사용자다`() {
        val q = ConversionMetricsQuery().apply { from = "2026-10-21"; to = "2026-10-21" }
        val web = service.funnel("web-first-visit", q)
        assertThat(web.steps.map { it.event }.take(2)).containsExactly("client_storyList_viewed", "client_storyCreate_viewed")
        assertThat(requests.single().requestUrl!!.queryParameter("n")).isEqualTo("new")
        assertThat(requests.single().requestUrl!!.queryParameterValues("e")).hasSize(6).allMatch { it!!.contains("country") && it.contains("Web") }
        for (name in listOf("android-first-visit", "story-creation", "story-detail-chat", "guest-limit-login")) {
            requests.clear(); service.funnel(name, q)
            assertThat(requests).isNotEmpty.allMatch { it.requestUrl!!.queryParameter("n") == "active" }
        }
        assertThat(service.funnel("guest-limit-login", q).steps.map { it.event }.take(2)).containsExactly("client_guestLimitDialog_shown", "client_guestLimitDialog_loginButton_clicked")
    }
    @Test fun `기능 사용의 간편 제작 실패율은 범위의 모든 날짜를 DB 값으로 채운다`() {
        val day = LocalDate.parse("2026-10-21")
        val reads = object : AdminMetricReadRepository(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(org.springframework.jdbc.datasource.DriverManagerDataSource())) {
            override fun storyCompletions(range: AnalyticsRange, excluded: List<Long>) = listOf(mapOf<String, Any?>("metric_day" to day.toEpochDay(), "completed" to 3L, "failed" to 2L))
        }
        val db = AdminMetricsService(MetricDefinitions(AnalyticsQueryFactory(mapper), mapper), fixture.snapshots, AnalyticsQueryFactory(mapper), reads, com.knk.manyak.admin.config.AdminDashboardProperties(), fixture.clock)
        val value = db.quality(PlatformMetricsQuery().apply { from = "2026-10-20"; to = "2026-10-21"; platform = "web" })
        assertThat(value.storyCompletion.map { it.date }).containsExactly(day.minusDays(1), day)
        assertThat(value.storyCompletion[0]).isEqualTo(StoryCompletionDay(day.minusDays(1), 0, 0, null))
        assertThat(value.storyCompletion[1]).isEqualTo(StoryCompletionDay(day, 5, 2, 0.4))
    }
}
