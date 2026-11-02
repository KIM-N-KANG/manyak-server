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
                    "series" to listOf(dates.map { if (url.queryParameter("e")!!.contains("inbox")) 0 else if (it == day) 5 else 30 }, dates.map { if (url.queryParameter("e")!!.contains("inbox")) 0 else 2 }, dates.map { 99 }, dates.map { 99 })
                ))))
            }
        }
    }
    @AfterEach fun stop() { server.shutdown() }
    private fun <T> anyValue(): T { org.mockito.ArgumentMatchers.any<T>(); @Suppress("UNCHECKED_CAST") return null as T }
    private fun campaign(link: Boolean? = true, started: LocalDate = day, delivered: Long = 100) = PushCampaignReach(id, PushKind.SERVICE, "점검", null, started.atStartOfDay(ZoneOffset.UTC).toInstant(), link, delivered)
    private fun service(measured: LocalDate? = day.minusDays(20), android: LocalDate? = null) = PushOpensService(delivery, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), PushOpensSettings(measured, android), fixture.clock, org.mockito.Mockito.mock(com.knk.manyak.admin.repository.PushChatConversionRepository::class.java))
    private fun query(from: LocalDate = day.minusDays(1), to: LocalDate = day) = AnalyticsRangeQuery().apply { this.from=from.toString();this.to=to.toString() }

    @Test fun `7일 웹 조회는 두 경로를 묶어 2회 호출하고 350 비용과 일별 값을 저장한다`() {
        val service = service()
        val result = service.read(AnalyticsRangeQuery().apply { days="7" })
        assertThat(result.activityTypes.map { it.type }).containsExactlyElementsOf(types)
        assertThat(requests).hasSize(2)
        val url = requests.first().requestUrl!!
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
        assertThat(fixture.budget.reserve(150)).isTrue();assertThat(fixture.budget.reserve(1)).isFalse()
        assertThat(service.read(AnalyticsRangeQuery())).isEqualTo(result)
        assertThat(requests).hasSize(2)
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
        assertThat(requests.first().requestUrl!!.queryParameter("start")).isEqualTo(measured.format(BASIC_ISO_DATE))
        verify(delivery).activityReach(measured,day,types)
    }
    @Test fun `남은 예산 150은 최근 3일만 조회하고 도달 분모는 전체 구간이다`() {
        fixture.budget.reserve(350)
        val result=service().read(query(day.minusDays(6)))
        assertThat(result.pendingDates).containsExactlyElementsOf((6L downTo 3L).map { day.minusDays(it) })
        assertThat(result.campaigns.single().deliveredMembers).isEqualTo(100)
        assertThat(result.campaigns.single().pushOpens).isEqualTo(65)
        assertThat(result.activityFrom).isEqualTo(day.minusDays(6))
        assertThat(requests.first().requestUrl!!.queryParameter("start")).isEqualTo(day.minusDays(2).format(BASIC_ISO_DATE))
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
        assertThat(requests).hasSize(2)
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
        // 만료 값으로 바로 응답하고 실패한 백그라운드 갱신의 쿨다운 동안 다시 부르지 않는다.
        assertThat(service.read(query())).isEqualTo(cached); fixture.executor.await()
        assertThat(service.read(query())).isEqualTo(cached)
        assertThat(requests).hasSize(4)
    }

    @Test fun `366일은 부분 응답을 허용하고 367일과 90일 빠른 범위는 거절한다`() {
        val service=service(day.minusDays(400))
        assertThat(service.read(query(day.minusDays(365))).pendingDates).hasSize(356)
        assertThatThrownBy { service.read(query(day.minusDays(366))) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThatThrownBy { service.read(AnalyticsRangeQuery().apply { days="90" }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    }
    @Test fun `앱과 웹 푸시 및 알림함 네 조회는 합계와 경로를 분리하고 300 비용을 쓴다`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val url = request.requestUrl!!
                val dates = AnalyticsRange(LocalDate.parse(url.queryParameter("start"), BASIC_ISO_DATE), LocalDate.parse(url.queryParameter("end"), BASIC_ISO_DATE)).dates()
                val event = fixture.mapper.readTree(url.queryParameter("e")!!)
                val app = event["event_type"].asText() == "client_notification_opened"
                val inbox = event["filters"][0]["subprop_value"][0].asText() == "inbox"
                val n = if (inbox) { if (app) 2 else 4 } else if (app) 10 else 30
                return MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to mapOf("xValues" to dates.map { it.toString() },
                    "seriesLabels" to listOf(listOf(0, id.toString())), "series" to listOf(dates.map { n })))))
            }
        }
        val result = service(android = day.minusDays(20)).read(query(day.minusDays(2)))
        val row = result.campaigns.single()
        assertThat(row.webPushOpens).isEqualTo(90); assertThat(row.androidPushOpens).isEqualTo(30)
        assertThat(row.pushOpens).isEqualTo(120); assertThat(row.pushOpenRate).isEqualTo(1.2)
        assertThat(row.webInboxOpens).isEqualTo(12); assertThat(row.androidInboxOpens).isEqualTo(6)
        assertThat(row.inboxOpens).isEqualTo(18)
        assertThat(result.androidMeasuredFrom).isEqualTo(day.minusDays(20))
        assertThat(requests).hasSize(4)
        requests.forEach {
            val url = it.requestUrl!!
            assertThat(url.queryParameter("m")).isEqualTo("uniques")
            assertThat(url.queryParameter("i")).isEqualTo("1"); assertThat(url.queryParameter("limit")).isEqualTo("1000")
            assertThat(url.queryParameter("s")).isNull()
            assertThat(url.queryParameter("e")).doesNotContain("country", "platform", "cohort")
        }
        assertThat(fixture.budget.reserve(200)).isTrue(); assertThat(fixture.budget.reserve(1)).isFalse()
    }

    @Test fun `대상 시작보다 늦게 측정한 앱은 이후 값이 있어도 null이고 조회 전 날짜는 pending이 아니다`() {
        `when`(delivery.campaignReach(anyValue(), anyValue())).thenReturn(listOf(campaign(started = day.minusDays(2))))
        val result = service(day.minusDays(10), day.minusDays(1)).read(query(day.minusDays(3)))
        assertThat(result.pendingDates).isEmpty()
        assertThat(result.activityFrom).isEqualTo(day.minusDays(3))
        assertThat(result.campaigns.single().androidPushOpens).isNull()
        assertThat(result.campaigns.single().androidInboxOpens).isNull()
        assertThat(result.activityTypes.map { it.androidPushOpens }).containsOnlyNulls()
        val apps = requests.filter { it.requestUrl!!.queryParameter("e")!!.contains("client_notification_opened") }
        assertThat(apps).hasSize(2)
        assertThat(apps.map { it.requestUrl!!.queryParameter("start") }).containsOnly(day.minusDays(1).format(BASIC_ISO_DATE))
    }

    @Test fun `캠페인 하나는 마지막 선점일부터 측정한 플랫폼만 조회하고 미선점과 없는 캠페인을 구분한다`() {
        `when`(delivery.campaignReach(id)).thenReturn(campaign(started = day.minusDays(1)))
        val result = service(day.minusDays(10), day).campaign(id)
        assertThat(result.from).isEqualTo(day.minusDays(1)); assertThat(result.to).isEqualTo(day)
        assertThat(result.androidPushOpens).isNull(); assertThat(result.pushOpens).isEqualTo(35)
        assertThat(requests).hasSize(2)
        `when`(delivery.campaignReach(id)).thenReturn(campaign().copy(startedAt = null, deepLinkIncluded = null, deliveredMembers = 0))
        val pending = service().campaign(id)
        assertThat(pending.openState).isEqualTo(PushOpenState.PENDING); assertThat(pending.from).isNull(); assertThat(pending.to).isNull()
        assertThat(pending.asOf).isNull(); assertThat(pending.pendingDates).isEmpty()
        `when`(delivery.campaignReach(id)).thenReturn(null)
        assertThatThrownBy { service().campaign(id) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        assertThat(requests).hasSize(2)
    }

    @Test fun `앱 조회의 시작 전 날짜는 웹만으로 준비되고 앱 마지막 날짜 누락만 pending이다`() {
        val service = service(day.minusDays(10), day.minusDays(1))
        val entries = service.batchMetrics()
        entries.forEach { entry ->
            val dates = if (entry.start == day.minusDays(1)) listOf(day.minusDays(1)) else AnalyticsRange(day.minusDays(3), day).dates()
            fixture.store.saveDaily(entry.metric.definition, dates.associateWith {
                AnalyticsValue(fixture.mapper.readTree("""[{"labels":["$id"],"value":1}]"""), fixture.clock.instant())
            })
        }
        fixture.budget.reserve(500)
        val result = service.read(query(day.minusDays(3)))
        assertThat(result.pendingDates).containsExactly(day)
        assertThat(result.campaigns.single().webPushOpens).isEqualTo(3)
        assertThat(result.campaigns.single().androidPushOpens).isEqualTo(1)
        assertThat(result.activityTypes.map { it.androidPushOpens }).containsOnlyNulls()
        assertThat(requests).isEmpty()
    }

    @Test fun `기존 push_opens 저장 정의를 그대로 재사용하고 알림함만 추가 조회한다`() {
        val event = CampaignEvents.event("[Amplitude] Page Viewed", listOf(CampaignEvents.filter("utm_source", listOf("push")))) +
            mapOf("group_by" to listOf(mapOf("type" to "event", "value" to "utm_campaign")))
        val old = AnalyticsQueryFactory(fixture.mapper).dailyGrouped("push_opens", event)
        fixture.store.saveDaily(old.definition, AnalyticsRange(day.minusDays(1), day).dates().associateWith {
            AnalyticsValue(fixture.mapper.readTree("""[{"labels":["$id"],"value":1}]"""), fixture.clock.instant())
        })
        val result = service().read(query())
        assertThat(result.campaigns.single().pushOpens).isEqualTo(2)
        assertThat(result.campaigns.single().inboxOpens).isZero()
        assertThat(requests).hasSize(1)
        assertThat(requests.single().requestUrl!!.queryParameter("e")).contains("inbox")
    }

    @Test fun `캠페인 단건은 목록 저장 값을 공유하고 366일 상한과 측정 아님을 지킨다`() {
        `when`(delivery.campaignReach(id)).thenReturn(campaign(started = day.minusDays(1)))
        val service = service()
        service.read(query())
        val result = service.campaign(id)
        assertThat(result.pushOpens).isEqualTo(35)
        assertThat(requests).hasSize(2)
        `when`(delivery.campaignReach(id)).thenReturn(campaign(link = false))
        assertThat(service.campaign(id).openState).isEqualTo(PushOpenState.NO_LINK)
        assertThat(service.campaign(id).asOf).isNull()
        `when`(delivery.campaignReach(id)).thenReturn(campaign(started = day.minusDays(400)))
        val before = requests.size
        val old = service(day.minusDays(500)).campaign(id)
        assertThat(old.to).isEqualTo(day.minusDays(35))
        // 앞의 2일 조회에 100을 썼으므로 남은 400으로 8일을 준비한다.
        assertThat(old.pendingDates).hasSize(358)
        assertThat(requests.drop(before).map { it.requestUrl!!.queryParameter("end") }).containsOnly(day.minusDays(35).format(BASIC_ISO_DATE))
    }

    @Test fun `웹 설정이 없어도 앱만으로 측정하고 캠페인 실패는 503과 쿨다운을 따른다`() {
        val appOnly = service(null, day.minusDays(10))
        val result = appOnly.read(query())
        assertThat(result.measuredFrom).isNull()
        assertThat(result.campaigns.single().webPushOpens).isNull()
        assertThat(result.campaigns.single().androidPushOpens).isEqualTo(35)
        assertThat(result.campaigns.single().pushOpens).isEqualTo(35)
        assertThat(result.activityFrom).isEqualTo(day.minusDays(1))
        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.requestUrl!!.queryParameter("e") }).allMatch { it!!.contains("client_notification_opened") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setResponseCode(500).setBody("{}")
            }
        }
        // 앞서 저장한 앱 조회와 다른 웹 조회 정의로 빈 저장소의 실패 경로를 검증한다.
        val other = java.util.UUID.randomUUID()
        `when`(delivery.campaignReach(other)).thenReturn(campaign(started = day.minusDays(3)).copy(campaignId = other))
        val failing = service(day.minusDays(10), null)
        repeat(2) { assertThatThrownBy { failing.campaign(other) }.isInstanceOf(AdminAnalyticsUnavailable::class.java) }
        assertThat(requests).hasSize(3)
    }

    @Test fun `캠페인 세 개 목록의 도달 시각은 한 번에 일괄 조회한다`() {
        val ids = List(3) { UUID.randomUUID() }
        `when`(delivery.campaignReach(anyValue(), anyValue())).thenReturn(ids.map { campaign(link = false).copy(campaignId = it) })
        val rows = ids.map { PushMemberDelivery(1, PushDeliveryType.NOTICE, fixture.clock.instant(), it) }
        `when`(delivery.reachedMemberDeliveries(ids)).thenReturn(rows)
        val conversions = mock(com.knk.manyak.admin.repository.PushChatConversionRepository::class.java)
        `when`(conversions.activity(rows)).thenReturn(rows.mapIndexed { i, row ->
            com.knk.manyak.admin.repository.PushDeliveryChatActivity(row, i != 1, false) })
        val result = PushOpensService(delivery, fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), PushOpensSettings(), fixture.clock, conversions).read(query())
        assertThat(result.campaigns.map { it.chattedMembers }).containsExactly(1L, 0L, 1L)
        verify(conversions).activity(rows)
        val calls = mockingDetails(delivery).invocations.filter { it.method.name == "reachedMemberDeliveries" && it.arguments.size == 1 }
        assertThat(calls).hasSize(1)
        assertThat(calls.single().arguments[0]).isEqualTo(ids)
    }

}
