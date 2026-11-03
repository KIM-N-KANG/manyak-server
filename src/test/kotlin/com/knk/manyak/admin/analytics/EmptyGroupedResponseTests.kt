package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate

class EmptyGroupedResponseTests {
    private lateinit var server: MockWebServer
    private lateinit var fixture: AnalyticsTestFixture
    private lateinit var campaigns: CampaignAnalyticsService
    private lateinit var metric: DailyAnalyticsMetric
    private val now = Instant.parse("2026-11-04T00:00:00Z")
    private val range = AnalyticsRange(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"))
    @BeforeEach fun setup() {
        server = MockWebServer().apply { start() }
        fixture = AnalyticsTestFixture(AmplitudeDashboardProperties("mock", "mock", dashboardBaseUrl = server.url("/").toString()), MutableAnalyticsClock(now))
        campaigns = CampaignAnalyticsService(CampaignSettings(), fixture.snapshots, AnalyticsQueryFactory(fixture.mapper), fixture.clock)
        val metrics = campaigns.appTransferMetrics()
        metric = metrics.last()
        metrics.dropLast(1).forEach { other ->
            fixture.store.saveDaily(other.definition, range.dates().associateWith { AnalyticsValue(fixture.mapper.valueToTree(0L), now, now) })
        }
    }
    @AfterEach fun cleanup() { fixture.snapshots.close(); server.shutdown() }
    private fun response(labels: String, series: String) = fixture.mapper.readTree(
        """{"xValues":["2026-07-01","2026-07-02"],"seriesLabels":$labels,"series":$series}""")
    private fun load(labels: String, series: String): AnalyticsSnapshotResult {
        server.enqueue(MockResponse().setBody(fixture.mapper.writeValueAsString(mapOf("data" to response(labels, series)))))
        return fixture.snapshots.load("campaigns/app-transfer", range, listOf(metric))
    }
    private fun assertEmptyStored(labels: String, series: String) {
        val result = load(labels, series)
        assertThat(result.progress.failure).isNull()
        assertThat(result.pendingDates()).isEmpty()
        val stored = fixture.store.daily(metric.definition, range.from, range.to)
        assertThat(stored.keys).containsExactlyInAnyOrderElementsOf(range.dates())
        stored.values.forEach { assertThat(it.value.isArray && it.value.isEmpty).isTrue() }
        val query = AnalyticsRangeQuery().apply { from = range.from.toString(); to = range.to.toString() }
        val app = campaigns.appTransfer(query)
        assertThat(app.pendingDates).isEmpty()
        assertThat(app.daily.map { it.date }).containsExactlyElementsOf(range.dates())
        assertThat(app.daily).allMatch { it.newUsersBySource.isEmpty() }
        assertThat(app.totals.newUsersBySource).isEmpty()
        assertThat(server.requestCount).isEqualTo(1) // 저장된 빈 배열은 미수집이 아니므로 다시 조회하지 않는다.
    }
    @ParameterizedTest @ValueSource(strings = ["[0]", "[[0]]", "[[0,1]]", "[[0,1,2]]"])
    fun `묶음 이름 없는 영 계열은 날짜마다 빈 배열로 저장하고 준비 중 날짜가 없다`(labels: String) {
        assertEmptyStored(labels, "[[0,0]]")
    }
    @Test fun `계열 없는 응답도 날짜마다 빈 배열로 저장하고 준비 중 날짜가 없다`() {
        assertEmptyStored("[]", "[]")
    }
    @ParameterizedTest @ValueSource(strings = ["[0]", "[[0]]", "[[0,1]]", "[[0,1,2]]"])
    fun `묶음 이름 없는 계열에 하나라도 영이 아니면 parse_error이고 저장하지 않는다`(labels: String) {
        val result = load(labels, "[[0,1]]")
        assertThat(result.progress.failure).isEqualTo(AnalyticsFailureReason.parse_error)
        assertThat(result.pendingDates()).containsExactlyElementsOf(range.dates())
        assertThat(fixture.store.daily(metric.definition, range.from, range.to)).isEmpty()
    }
    @ParameterizedTest @ValueSource(strings = ["true", "{}", "[]", "[true]", "[0,{}]", "0.0", "[0.0]", "[0,0.0]", "[{},0]", "[[0]]"])
    fun `정수 인덱스 모양이 아닌 이름 없는 라벨은 영 계열이어도 parse_error이고 저장하지 않는다`(label: String) {
        val result = load("[$label]", "[[0,0]]")
        assertThat(result.progress.failure).isEqualTo(AnalyticsFailureReason.parse_error)
        assertThat(result.pendingDates()).containsExactlyElementsOf(range.dates())
        assertThat(fixture.store.daily(metric.definition, range.from, range.to)).isEmpty()
    }
    @Test fun `빈 계열만 버리고 이름 있는 계열과 none 및 null 묶음은 그대로 보존한다`() {
        val data = response("""[0,[0],[0,"ig"],[0,"(none)"],[0,null]]""", "[[0,0],[0,0],[2,3],[4,5],[6,7]]")
        val result = AmplitudeResults.dailyGroups(data, range)
        assertThat(result[range.from]).containsExactly(
            AmplitudeResults.Group(listOf("ig"), 2), AmplitudeResults.Group(listOf(null), 4), AmplitudeResults.Group(listOf(null), 6))
        assertThat(result[range.to]).containsExactly(
            AmplitudeResults.Group(listOf("ig"), 3), AmplitudeResults.Group(listOf(null), 5), AmplitudeResults.Group(listOf(null), 7))
    }
    @ParameterizedTest @ValueSource(strings = ["[0]", "[0,null]", "[0,-1]", "[0,0.0]", "[0,\"0\"]"])
    fun `이름 없는 계열도 날짜 수와 음이 아닌 정수 검증을 생략하지 않는다`(values: String) {
        for (labels in listOf("[0]", "[[0]]")) {
            assertThatThrownBy { AmplitudeResults.dailyGroups(response(labels, "[$values]"), range) }
                .isInstanceOfSatisfying(AmplitudeFetchFailure::class.java) { assertThat(it.reason).isEqualTo(AnalyticsFailureReason.parse_error) }
        }
    }
}
