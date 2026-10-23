package com.knk.manyak.admin.analytics

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

class AmplitudeResponseCompatibilityTests {
    private val mapper = ObjectMapper()
    private val range = AnalyticsRange(LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-23"))
    private val definitions = MetricDefinitions(AnalyticsQueryFactory(mapper), mapper)
    @Test fun `MAU는 최근 30일 범위 고유 사용자만 읽고 일별 값을 합하지 않는다`() {
        val metric = definitions.mau(range.to)
        assertThat(metric.definition.path).isEqualTo("/api/2/events/segmentation")
        assertThat(metric.definition.parameters.toMap()["e"]).contains("_active", "country")
        assertThat(metric.definition.parameters.toMap()["s"]).isNull()
        assertThat(metric.range.days).isEqualTo(30)
        assertThat(metric.definition.perDay).isEqualTo(1)
        val data = mapper.readTree("""{"series":[[7,7]],"seriesCollapsed":[[{"value":9}]]}""")
        assertThat(metric.decode(data).asLong()).isEqualTo(9)
    }
    @Test fun `정상 빈 퍼널 결과는 모든 단계가 0이고 망가진 결과는 실패한다`() {
        assertThat(AmplitudeResults.funnel(mapper.readTree("[]"), 6)).containsExactly(0,0,0,0,0,0)
        for (body in listOf("null", "{}", "[{}]", "[{\"cumulativeRaw\":[0]}]")) {
            val failure = catchThrowable { AmplitudeResults.funnel(mapper.readTree(body), 6) } as AmplitudeFetchFailure
            assertThat(failure.reason.name).isEqualTo("parse_error")
        }
    }
    @Test fun `200의 data 누락 null 잘못된 퍼널은 parse_error와 HTTP 상태를 유지한다`() {
        MockWebServer().use { server ->
            server.start()
            val client = AmplitudeRestClient(AmplitudeDashboardProperties("key", "secret", dashboardBaseUrl=server.url("/").toString()),mapper,AmplitudeBudget())
            val metric = AnalyticsQueryFactory(mapper).funnel("probe", range, List(2) { AdminMetricEvents.event("_active") })
            for (body in listOf("{}", "{\"data\":null}", "{\"data\":[{\"cumulativeRaw\":[0]}]}")) {
                server.enqueue(MockResponse().setBody(body))
                val failure = catchThrowable { client.fetchDecoded(metric.definition, range, System.nanoTime()+5_000_000_000, decode=metric.decode) } as AmplitudeFetchFailure
                assertThat(failure.reason.name).isEqualTo("parse_error")
                assertThat(failure.status).isEqualTo(200)
            }
        }
    }
    @Test fun `세션의 실제 ISO 날짜시각은 같은 날짜의 평균으로 읽는다`() {
        val data = mapper.readTree("""{"xValues":["2026-10-23T00:00:00"],"series":[[61.5]]}""")
        val values = definitions.sessions().first().decode(data, range)
        assertThat(values[range.from]!!.asDouble()).isEqualTo(61.5)
    }
    @Test fun `연도가 포함된 실제 리텐션 코호트 키를 읽는다`() {
        val data = mapper.readTree("""{"series":[{"values":{"Oct 23, 2026":[{"count":3,"outof":3,"incomplete":false}]}}]}""")
        assertThat(definitions.retention().first().decode(data, range)[range.from]!![0]["outof"].asLong()).isEqualTo(3)
    }
    @Test fun `공통 날짜 해석은 기존 날짜와 ISO 날짜시각을 받고 중복 날짜나 잘못된 값은 거절한다`() {
        for (date in listOf("2026-10-23", "2026-10-23T00:00:00", "2026-10-23T00:00:00+09:00")) {
            val data = mapper.readTree("""{"xValues":["$date"],"series":[[7]]}""")
            assertThat(AmplitudeResults.series(data, range)).containsExactly(7)
        }
        for (date in listOf("2026-02-30T00:00:00", "2026-10-23junk")) {
            assertThatThrownBy { AmplitudeResults.series(mapper.readTree("""{"xValues":["$date"],"series":[[7]]}"""),range) }.isInstanceOf(AmplitudeFetchFailure::class.java)
        }
    }
    @Test fun `비 JSON 성공 본문의 파싱 오류는 200과 예외 클래스만 보존한다`() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("private response content"))
            val p = AmplitudeDashboardProperties("private-key", "private-secret", dashboardBaseUrl=server.url("/").toString())
            val client = AmplitudeRestClient(p,mapper,AmplitudeBudget())
            val definition = definitions.sessions().first().definition
            val failure = catchThrowable { client.fetch(definition,range,System.nanoTime()+5_000_000_000) } as AmplitudeFetchFailure
            assertThat(failure.status).isEqualTo(200)
            assertThat(failure.reason.name).isEqualTo("parse_error")
            assertThat(failure).extracting("phase").isEqualTo("response_decode")
            assertThat(failure).extracting("exceptionClass").asString().contains("jackson")
            assertThat(failure.message).doesNotContain("private")
            assertThat(failure.cause).isNull()
        }
    }
    @Test fun `응답 없는 접속 실패는 실제 예외 클래스와 null 상태를 보존한다`() {
        val server = MockWebServer(); server.start(); val url = server.url("/").toString(); server.shutdown()
        val client = AmplitudeRestClient(AmplitudeDashboardProperties("key", "secret", dashboardBaseUrl=url),mapper,AmplitudeBudget())
        val failure = catchThrowable { client.fetch(definitions.sessions().first().definition,range,System.nanoTime()+5_000_000_000) } as AmplitudeFetchFailure
        assertThat(failure.status).isNull()
        assertThat(failure.reason.name).isEqualTo("http_error")
        assertThat(failure).extracting("exceptionClass").isEqualTo("java.net.ConnectException")
        assertThat(failure).extracting("phase").isEqualTo("request")
        assertThat(failure.cause).isNull()
    }

    @Test fun `HTTP 오류 코드는 상태와 안전한 코드만 보존하고 HTML이나 원문은 숨긴다`() {
        MockWebServer().use { server ->
            server.start()
            val client = AmplitudeRestClient(AmplitudeDashboardProperties("key", "secret", dashboardBaseUrl=server.url("/").toString()),mapper,AmplitudeBudget())
            for ((body, expected) in listOf("""{"code":"Forbidden","message":"private body"}""" to "Forbidden", """{"error":{"code":229,"message":"private body"}}""" to "229", "<html>private</html>" to null, """{"error_code":"private key=value"}""" to null)) {
                server.enqueue(MockResponse().setResponseCode(403).setBody(body))
                val failure = catchThrowable { client.fetch(definitions.sessions().first().definition,range,System.nanoTime()+5_000_000_000) } as AmplitudeFetchFailure
                assertThat(failure.status).isEqualTo(403)
                assertThat(failure.reason.name).isEqualTo("http_error")
                assertThat(failure).extracting("errorCode").isEqualTo(expected)
                assertThat(failure.message).doesNotContain("private")
            }
        }
    }
}
