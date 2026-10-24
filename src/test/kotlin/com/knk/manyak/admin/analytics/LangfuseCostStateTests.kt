package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*

/** 네트워크와 무관한 날짜 유효 판정·저장 재사용 회귀. HTTP 계약은 MockWebServer 테스트에서 검증한다. */
class LangfuseCostStateTests {
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T03:00:00Z"))
    private val fixture = AnalyticsTestFixture(AmplitudeDashboardProperties(), clock)
    private val properties = AdminLangfuseProperties("https://example.invalid", "public", "secret", "prod", callInterval = Duration.ofMillis(1))
    private val calls = mutableListOf<LangfuseInterval>()
    private var fail: LangfuseFetchFailure? = null
    private val client = object : LangfuseMetricsClient(properties, fixture.mapper) {
        override fun fetch(interval: LangfuseInterval, now: Instant): LangfuseMetricsPage {
            calls.add(interval)
            fail?.let { throw it }
            return LangfuseMetricsPage(interval.dates().associateWith { emptyList() })
        }
    }
    private fun snapshots() = LangfuseCostSnapshots(fixture.store, client, properties, fixture.mapper, clock)
    private val today = LocalDate.parse("2026-10-23")

    @Test fun `90일은 최근 18일을 먼저 저장하고 재시작 뒤 다음 18일만 받는다`() {
        val range = AnalyticsRange(today.minusDays(89), today)
        val s = snapshots()
        assertThat(s.load(range)).hasSize(18)
        assertThat(calls).containsExactlyElementsOf((0L..5L).map { LangfuseInterval(AnalyticsRange(today.minusDays(2 + it * 3), today.minusDays(it * 3))) })
        assertThat(snapshots().load(range)).hasSize(36)
        assertThat(calls.last()).isEqualTo(LangfuseInterval(AnalyticsRange(today.minusDays(35), today.minusDays(33))))
        assertThat(fixture.store.daily(s.definition, range.from, range.to).values.count { it.finalizedAt != null }).isEqualTo(34)
    }

    @Test fun `어제 한 시간 오늘 십 분 확정일은 영구 재사용한다`() {
        val range = AnalyticsRange(today.minusDays(8), today)
        val s = snapshots()
        s.load(range); s.load(range)
        assertThat(calls).hasSize(3)
        val saved = fixture.store.daily(s.definition, range.from, range.to)
        assertThat(saved).hasSize(9)
        assertThat(saved.values.count { it.finalizedAt != null }).isEqualTo(7)
        assertThat(saved[LocalDate.parse("2026-10-17")]!!.value.toString()).isEqualTo("[]")
        clock.at = clock.at.plusSeconds(600)
        s.load(range)
        assertThat(calls.last()).isEqualTo(LangfuseInterval(AnalyticsRange(today, today)))
        clock.at = clock.at.plusSeconds(3000)
        s.load(range)
        assertThat(calls.last()).isEqualTo(LangfuseInterval(AnalyticsRange(today.minusDays(1), today)))
        assertThat(calls).hasSize(5)
    }

    @Test fun `자정 전 받은 값은 어제 값으로 재조회한 뒤 확정일이 되면 고정한다`() {
        val range = AnalyticsRange(today, today)
        clock.at = Instant.parse("2026-10-23T14:59:00Z")
        val s = snapshots()
        s.load(range)
        clock.at = Instant.parse("2026-10-23T15:01:00Z")
        s.load(range)
        assertThat(calls).hasSize(2)
        clock.at = Instant.parse("2026-10-24T15:01:00Z")
        snapshots().load(range)
        assertThat(calls).hasSize(2)
        assertThat(fixture.store.daily(s.definition, today, today)[today]!!.finalizedAt).isNotNull()
    }

    @Test fun `429 쿨다운은 모든 범위에 적용하고 만료된 값도 보존한다`() {
        val s = snapshots()
        val range = AnalyticsRange(today, today)
        val first = s.load(range)
        clock.at = clock.at.plusSeconds(600)
        fail = LangfuseFetchFailure(AiCostFailureReason.rate_limited, 429, Duration.ofSeconds(120))
        assertThat(s.load(range)).isEqualTo(first)
        assertThat(s.load(AnalyticsRange(today.minusDays(1), today))).isEqualTo(first)
        clock.at = clock.at.plusSeconds(119)
        s.load(range)
        assertThat(calls).hasSize(2)
        clock.at = clock.at.plusSeconds(1)
        fail = null
        s.load(range)
        assertThat(calls).hasSize(3)
    }

    @Test fun `첫 호출 성공 둘째 실패는 첫 구간을 저장하고 나머지만 준비 중으로 남긴다`() {
        val failing = object : LangfuseMetricsClient(properties, fixture.mapper) {
            var count = 0
            override fun fetch(interval: LangfuseInterval, now: Instant): LangfuseMetricsPage {
                if (++count == 2) throw LangfuseFetchFailure(AiCostFailureReason.http_error, 500)
                return LangfuseMetricsPage(interval.dates().associateWith { emptyList() })
            }
        }
        val s = LangfuseCostSnapshots(fixture.store, failing, properties, fixture.mapper, clock)
        val range = AnalyticsRange(today.minusDays(89), today)
        assertThat(s.load(range)).hasSize(3)
        assertThat(s.load(range)).hasSize(3)
        assertThat(failing.count).isEqualTo(2)
    }
    @Test fun `자정을 넘겨 도착한 응답은 조회 시작 당시 미완료 날짜를 확정하지 않는다`() {
        clock.at = Instant.parse("2026-10-23T14:59:59Z")
        var count = 0
        val crossing = object : LangfuseMetricsClient(properties, fixture.mapper) {
            override fun fetch(interval: LangfuseInterval, now: Instant): LangfuseMetricsPage {
                count++
                clock.at = clock.at.plusSeconds(2)
                return LangfuseMetricsPage(interval.dates().associateWith { emptyList() })
            }
        }
        val s = LangfuseCostSnapshots(fixture.store, crossing, properties, fixture.mapper, clock)
        val range = AnalyticsRange(today, today)
        s.load(range)
        clock.at = Instant.parse("2026-10-24T15:01:00Z")
        s.load(range)
        assertThat(count).isEqualTo(2)
    }

    @Test fun `주소 공개 키 환경은 저장 키를 바꾸고 비밀 키 교체는 확정 값을 재사용한다`() {
        var count = 0
        fun source(host: String = "https://example.invalid", publicKey: String = "original-public-key", secret: String = "original-secret-key", environment: String = "prod"): LangfuseCostSnapshots {
            val p = AdminLangfuseProperties(host, publicKey, secret, environment)
            val c = object : LangfuseMetricsClient(p, fixture.mapper) {
                override fun fetch(interval: LangfuseInterval, now: Instant): LangfuseMetricsPage {
                    count++
                    return LangfuseMetricsPage(interval.dates().associateWith { emptyList() })
                }
            }
            return LangfuseCostSnapshots(fixture.store, c, p, fixture.mapper, clock)
        }
        val day = AnalyticsRange(today.minusDays(2), today.minusDays(2))
        val original = source()
        original.load(day)
        val rotatedSecret = source(secret = "rotated-secret-key")
        rotatedSecret.load(day)
        assertThat(count).isEqualTo(1)
        assertThat(rotatedSecret.definition.hash).isEqualTo(original.definition.hash)
        val publicChanged = source(publicKey = "different-public-key")
        assertThat(publicChanged.definition.params).isEqualTo(original.definition.params)
        for (changed in listOf(publicChanged, source(environment = "staging"), source(host = "https://other.invalid"))) {
            assertThat(changed.definition.hash).isNotEqualTo(original.definition.hash)
            changed.load(day)
            assertThat(changed.definition.params).doesNotContain("original-public-key", "different-public-key", "original-secret-key", "publicKey")
        }
        assertThat(count).isEqualTo(4)
        source(host = "https://example.invalid/").load(day)
        assertThat(count).isEqualTo(4)
        val changedQuery = AnalyticsDefinition(original.definition.metric, original.definition.path,
            original.definition.parameters.map { (key, value) -> key to if (key == "query") value.replace("totalTokens", "inputTokens") else value },
            0, fixture.mapper, hashParameters = listOf("publicKey" to "original-public-key"))
        assertThat(changedQuery.hash).isNotEqualTo(original.definition.hash)
    }

}
