package com.knk.manyak.admin.analytics

import com.knk.manyak.push.dto.*
import com.knk.manyak.push.service.PushDeliveryService
import okhttp3.mockwebserver.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import tools.jackson.databind.JsonNode
import java.time.*
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE
import java.util.UUID

class PushOpensTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var delivery: PushDeliveryService
    private val day = LocalDate.parse("2026-10-24")
    private val id = UUID.fromString("4b7f0c2a-9d1e-4f3a-8c5b-6e2d1a0f9b37")
    private val types = listOf(PushDeliveryType.STORY_COMPLETED, PushDeliveryType.STORY_MODERATION_COMPLETED, PushDeliveryType.ATTENDANCE_REMINDER, PushDeliveryType.CREDIT_EXPIRY_REMINDER)
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(Instant.parse("2026-10-24T03:00:00Z")))
        delivery = mock(PushDeliveryService::class.java)
        `when`(delivery.campaignReach(anyValue(), anyValue())).thenReturn(listOf(campaign()))
        `when`(delivery.activityReach(anyValue(), anyValue(), anyValue())).thenReturn(emptyList())
        setupValidDispatcher()
    }
    private fun setupValidDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                val from = LocalDate.parse(url.queryParameter("start"), BASIC_ISO_DATE)
                val to = LocalDate.parse(url.queryParameter("end"), BASIC_ISO_DATE)
                val dates = from.datesUntil(to.plusDays(1)).toList()
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf(
                    "xValues" to dates.map { it.toString() },
                    "seriesLabels" to listOf(listOf(0, id.toString()), listOf(0, "attendance_reminder"), listOf(0, "KR_META_WEB_ACTIVATION_COLD_202610"), listOf(0, "(none)")),
                    "series" to listOf(dates.map { if (it == day) 5 else 30 }, dates.map { 2 }, dates.map { 99 }, dates.map { 99 })
                ))))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun <T> anyValue(): T { org.mockito.ArgumentMatchers.any<T>(); @Suppress("UNCHECKED_CAST") return null as T }
    private fun campaign(link: Boolean? = true, started: LocalDate = day, delivered: Long = 100) = PushCampaignReach(id, PushKind.SERVICE, "점검", null, started.atStartOfDay(ZoneOffset.UTC).toInstant(), link, delivered)
    private fun service(measured: LocalDate? = day.minusDays(20)) = PushOpensService(delivery, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), PushOpensSettings(measured), fixture.clock)
    private fun query(from: LocalDate = day.minusDays(1), to: LocalDate = day) = AnalyticsRangeQuery().apply { this.from=from.toString();this.to=to.toString() }

    @Test fun `7일 조회는 이벤트 속성만 묶어 1회 호출하고 175 비용과 일별 값을 저장한다`() {
        val service = service()
        val result = service.read(AnalyticsRangeQuery().apply { days="7" })
        assertThat(result.activityTypes.map { it.type }).containsExactlyElementsOf(types)
        assertThat(requests).hasSize(1)
        val url = requests.single().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/api/2/events/segmentation")
        assertThat(url.queryParameter("m")).isEqualTo("uniques")
        assertThat(url.queryParameter("i")).isEqualTo("1")
        assertThat(url.queryParameter("limit")).isEqualTo("1000")
        assertThat(url.queryParameter("s")).isNull()
        val event = fixture.mapper.readTree(url.queryParameter("e")!!)
        assertThat(event["event_type"].asText()).isEqualTo("[Amplitude] Page Viewed")
        assertThat(event["filters"].size()).isEqualTo(1)
        assertThat(event["filters"][0]).isEqualTo(fixture.mapper.readTree("""{"subprop_type":"event","subprop_key":"utm_source","subprop_op":"is","subprop_value":["push"]}"""))
        assertThat(event["group_by"]).isEqualTo(fixture.mapper.readTree("""[{"type":"event","value":"utm_campaign"}]"""))
        assertThat(event.toString()).doesNotContain("country", "platform", "cohort", "initial_utm")
        assertThat(fixture.budget.reserve(325)).isTrue();assertThat(fixture.budget.reserve(1)).isFalse()
        assertThat(service.read(AnalyticsRangeQuery())).isEqualTo(result)
        assertThat(requests).hasSize(1)
    }
    @Test fun `날짜별 열람 합과 캠페인 및 종류 대응으로 35 나누기 100을 반환한다`() {
        val result = service().read(query())
        assertThat(result.campaigns.single().pushOpens).isEqualTo(35)
        assertThat(result.campaigns.single().pushOpenRate).isEqualTo(0.35)
        assertThat(result.campaigns.single().openState).isEqualTo(PushOpenState.MEASURED)
        assertThat(result.activityTypes.single { it.type == PushDeliveryType.ATTENDANCE_REMINDER }.pushOpens).isEqualTo(4)
        assertThat(result.activityTypes.first().pushOpens).isZero()
        assertThat(result.activityTypes.first().pushOpenRate).isNull()
        assertThat(result.activityTypes.single { it.type == PushDeliveryType.ATTENDANCE_REMINDER }.pushOpenRate).isNull()
    }
    @Test fun `측정 전 우선 판정과 NO_LINK PENDING을 구분한다`() {
        `when`(delivery.campaignReach(anyValue(), anyValue())).thenReturn(listOf(campaign(false),campaign(null),campaign(true, day.minusDays(4))))
        val result = service(day.minusDays(2)).read(query())
        assertThat(result.campaigns.map { it.openState }).containsExactly(PushOpenState.NO_LINK,PushOpenState.PENDING,PushOpenState.BEFORE_MEASUREMENT)
        assertThat(result.campaigns.map { it.pushOpens }).containsOnlyNulls()
        assertThat(result.campaigns.map { it.pushOpenRate }).containsOnlyNulls()
    }
    @Test fun `설정 없거나 범위 전체가 측정 전이면 조회 없이 정상 응답하고 도달은 전체 범위다`() {
        for (measured in listOf(null,day.plusDays(1))) {
            val result = service(measured).read(query())
            assertThat(result.asOf).isNull();assertThat(result.pendingDates).isEmpty();assertThat(result.activityFrom).isNull()
            assertThat(result.campaigns.single().openState).isEqualTo(PushOpenState.BEFORE_MEASUREMENT)
            assertThat(result.activityTypes.map { it.openState }).containsOnly(PushOpenState.BEFORE_MEASUREMENT)
            assertThat(result.activityTypes.map { it.pushOpens }).containsOnlyNulls()
        }
        verify(delivery,times(2)).activityReach(day.minusDays(1),day,types)
        assertThat(requests).isEmpty()
    }
    @Test fun `집계 구간을 측정일부터 자르고 시작 전 날짜를 pending에 넣지 않는다`() {
        val measured=day.minusDays(3)
        `when`(delivery.activityReach(measured,day,types)).thenReturn(listOf(PushActivityReach(PushDeliveryType.ATTENDANCE_REMINDER,measured,2)))
        val result=service(measured).read(query(day.minusDays(6)))
        assertThat(result.activityFrom).isEqualTo(measured)
        assertThat(result.activityTypes.single { it.type == PushDeliveryType.ATTENDANCE_REMINDER }.deliveredMembers).isEqualTo(2)
        assertThat(result.activityTypes.single { it.type == PushDeliveryType.ATTENDANCE_REMINDER }.pushOpenRate).isEqualTo(4.0)
        assertThat(result.pendingDates).isEmpty()
        assertThat(requests.single().requestUrl!!.queryParameter("start")).isEqualTo(measured.format(BASIC_ISO_DATE))
        verify(delivery).activityReach(measured,day,types)
    }
    @Test fun `남은 예산 75는 최근 3일만 조회하고 도달 분모는 전체 구간이다`() {
        fixture.budget.reserve(425)
        val result=service().read(query(day.minusDays(6)))
        assertThat(result.pendingDates).containsExactlyElementsOf((6L downTo 3L).map { day.minusDays(it) })
        assertThat(result.campaigns.single().deliveredMembers).isEqualTo(100)
        assertThat(result.campaigns.single().pushOpens).isEqualTo(65)
        assertThat(result.activityFrom).isEqualTo(day.minusDays(6))
        assertThat(requests.single().requestUrl!!.queryParameter("start")).isEqualTo(day.minusDays(2).format(BASIC_ISO_DATE))
    }
    @Test fun `하루 예산도 없으면 503이고 쿨다운 중 재호출하지 않는다`() {
        fixture.budget.reserve(490)
        val service=service()
        assertThatThrownBy { service.read(query()) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThatThrownBy { service.read(query()) }.isInstanceOf(AdminAnalyticsUnavailable::class.java)
        assertThat(requests).isEmpty()
    }

    @Test fun `날짜 합 3을 도달 2로 나눈 열람률은 1점5이고 빈 그룹 날짜도 저장한다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setBody("""{"data":{"xValues":["2026-10-23","2026-10-24"],"seriesLabels":[[0,"$id"]],"series":[[1,2]]}}""")
            }
        }
        `when`(delivery.campaignReach(anyValue(), anyValue())).thenReturn(listOf(campaign(delivered=2)))
        val result = service().read(query())
        assertThat(result.campaigns.single().pushOpenRate).isEqualTo(1.5)
        assertThat(result.activityTypes.map { it.type }).containsExactlyElementsOf(types)
        assertThat(result.activityTypes.map { it.openState }).containsOnly(PushOpenState.MEASURED)
        assertThat(result.activityTypes.map { it.deliveredMembers }).containsOnly(0L)
        assertThat(result.activityTypes.map { it.pushOpens }).containsOnly(0L)
        assertThat(result.activityTypes.map { it.pushOpenRate }).containsOnlyNulls()
    }
    @Test fun `정상 빈 그룹은 0으로 캐시하지만 날짜나 값이 잘못된 응답은 저장하지 않는다`() {
        val range = AnalyticsRange(day.minusDays(1), day)
        for (body in listOf(
            """{"xValues":["2026-10-23","2026-10-23"],"seriesLabels":[],"series":[]}""",
            """{"xValues":["2026-10-23","2026-10-24"],"seriesLabels":[[0,"x"]],"series":[[1]]}""",
            """{"xValues":["2026-10-23","2026-10-24"],"seriesLabels":[[0,"x"]],"series":[[1,-1]]}"""
        )) {
            val failure = catchThrowable { AmplitudeResults.dailyGroups(fixture.mapper.readTree(body), range) }
            assertThat(failure).isInstanceOf(AmplitudeFetchFailure::class.java)
            assertThat((failure as AmplitudeFetchFailure).reason).isEqualTo(AnalyticsFailureReason.parse_error)
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setBody("""{"data":{"xValues":["2026-10-23","2026-10-24"],"seriesLabels":[],"series":[]}}""")
            }
        }
        val service = service()
        val result = service.read(query())
        assertThat(result.campaigns.single().pushOpens).isZero()
        assertThat(result.pendingDates).isEmpty()
        assertThat(service.read(query())).isEqualTo(result)
        assertThat(requests).hasSize(1)
    }
    @Test fun `해석 실패는 빈 저장소에서 503이며 쿨다운 후 재시도하고 만료 캐시를 유지한다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setBody("""{"data":{"series":[]}}""")
            }
        }
        val service = service()
        repeat(2) { assertThatThrownBy { service.read(query()) }.isInstanceOf(AdminAnalyticsUnavailable::class.java) }
        assertThat(requests).hasSize(1)
        fixture.clock.at = fixture.clock.at.plusSeconds(61)
        setupValidDispatcher()
        val cached = service.read(query())
        assertThat(cached.campaigns.single().pushOpens).isEqualTo(35)
        fixture.clock.at = fixture.clock.at.plusSeconds(601)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setResponseCode(500).setBody("{}")
            }
        }
        assertThat(service.read(query())).isEqualTo(cached)
        assertThat(service.read(query())).isEqualTo(cached)
        assertThat(requests).hasSize(3)
    }

    @Test fun `366일은 부분 응답을 허용하고 367일과 90일 빠른 범위는 거절한다`() {
        val service=service(day.minusDays(400))
        assertThat(service.read(query(day.minusDays(365))).pendingDates).hasSize(346)
        assertThatThrownBy { service.read(query(day.minusDays(366))) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { service.read(AnalyticsRangeQuery().apply { days="90" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
}
