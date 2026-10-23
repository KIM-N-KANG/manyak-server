package com.knk.manyak.admin.analytics

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.Instant

class LangfuseMetricsParsingTests {
    private val mapper = ObjectMapper()
    private val client = LangfuseMetricsClient(AdminLangfuseProperties(), mapper)
    private val range = AnalyticsRange(LocalDate.parse("2026-10-21"), LocalDate.parse("2026-10-22"))
    private fun parse(rows: String) = client.decode(mapper.readTree("{\"data\":$rows}"), LangfuseInterval(range)).entries
    private fun row(time: String = "2026-10-21T14:00:00Z", cost: String = "0.004", tokens: String = "1000", model: String = "null") =
        """{"time_dimension":"$time","traceName":"채팅 턴","providedModelName":$model,"sum_totalCost":$cost,"sum_totalTokens":$tokens}"""
    @Test fun `숫자와 숫자 문자열을 합산하고 UTC 자정이 아닌 KST 자정으로 나눈다`() {
        val values = parse("[${row()},${row(cost = "\"0.001\"", tokens = "\"2000\"")},${row(time = "2026-10-21T15:00:00Z")}]")
        assertThat(values[range.from]!!.single().costUsd).isEqualByComparingTo("0.005")
        assertThat(values[range.from]!!.single().totalTokens).isEqualTo(3000)
        assertThat(values[range.to]!!.single().costUsd).isEqualByComparingTo("0.004")
        assertThat(values[range.from]!!.single().model).isNull()
    }
    @Test fun `천 행은 부분 결과를 버리고 재조회 대상으로 돌려준다`() {
        val root = mapper.readTree(List(1000) { row() }.joinToString(",", "{\"data\":[", "]}"))
        val page = client.decode(root, LangfuseInterval(range))
        assertThat(page.rowLimitReached).isTrue()
        assertThat(page.entries).isEmpty()
    }
    @Test fun `null과 빈 모델 이름을 구분한다`() {
        val values = parse("[${row()},${row(model = "\"\"")}]")
        assertThat(values[range.from]).hasSize(2)
        assertThat(values[range.from]!!.single { it.model == null }.costUsd).isEqualByComparingTo("0.004")
        assertThat(values[range.from]!!.single { it.model == "" }.totalTokens).isEqualTo(1000)
    }
    @Test fun `논리 범위 밖 시간 구간은 버리고 끝 경계를 다음 날짜에 한 번만 더한다`() {
        val rows = "[${row(time = "2026-10-20T14:00:00Z")},${row(time = "2026-10-20T15:00:00Z")},${row(time = "2026-10-22T15:00:00Z")}]"
        val values = parse(rows)
        assertThat(values[range.from]!!.single().costUsd).isEqualByComparingTo("0.004")
        assertThat(values[range.to]).isEmpty()
        val interval = LangfuseInterval(Instant.parse("2026-10-21T13:00:00Z"), Instant.parse("2026-10-21T14:00:00Z"))
        val page = client.decode(mapper.readTree("{\"data\":[${row(time = "2026-10-21T12:00:00Z")},${row(time = "2026-10-21T13:00:00Z")},${row()}]}"), interval)
        assertThat(page.entries[range.from]!!.single().costUsd).isEqualByComparingTo("0.004")
    }
    @Test fun `응답에 행이 없는 날짜도 확인된 빈 비용으로 채운다`() {
        assertThat(parse("[]")).containsExactlyInAnyOrderEntriesOf(range.dates().associateWith { emptyList<LangfuseCostEntry>() })
    }
    @Test fun `누락 필드 잘못된 숫자 음수 시간 단위 오류를 부분 저장하지 않는다`() {
        for (invalid in listOf("{}", row(cost = "\"oops\""), row(cost = "null"), row(cost = "-1"),
            row(tokens = "1.5"), row(tokens = "\"9223372036854775808\""), row(time = "2026-10-21T14:01:00Z"))) {
            assertThatThrownBy { parse("[${row()},$invalid]") }.isInstanceOf(Exception::class.java)
        }
        assertThatThrownBy { client.decode(mapper.readTree("{}"), LangfuseInterval(range)) }.isInstanceOf(Exception::class.java)
    }
}
