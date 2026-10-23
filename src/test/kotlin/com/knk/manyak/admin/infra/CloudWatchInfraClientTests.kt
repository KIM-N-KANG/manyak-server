package com.knk.manyak.admin.infra

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import java.time.*
import com.knk.manyak.admin.analytics.MutableAnalyticsClock

class CloudWatchInfraClientTests {
    private val sdk = mock(CloudWatchClient::class.java)
    private val clock = MutableAnalyticsClock(Instant.parse("2026-10-23T00:00:00Z"))
    private val client = CloudWatchInfraClient(sdk, InfraMetricsProperties(), clock)
    private val from = clock.instant().minusSeconds(3600); private val until = clock.instant()
    private fun query(id: String) = MetricDataQuery.builder().id(id).metricStat(MetricStat.builder()
        .metric(Metric.builder().namespace("AWS/ECS").metricName("CPUUtilization").dimensions(Dimension.builder().name("ServiceName").value(id).build()).build())
        .stat("Average").period(300).build()).returnData(true).build()
    private fun result(id: String, value: Double = 1.0, status: StatusCode = StatusCode.COMPLETE) = MetricDataResult.builder().id(id)
        .statusCode(status).timestamps(from).values(value).build()
    @Test fun `다중 지표는 묶어 보내고 ID로 대응해 5분 캐시하며 범위별로 분리한다`() {
        val requests = mutableListOf<GetMetricDataRequest>()
        `when`(sdk.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer {
            requests += it.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(result("ai", 2.0), result("server")).build()
        }
        val queries = listOf(query("server"), query("ai"))
        val first = client.fetch(queries, from, until)
        assertThat(first.series["server"]!!.single().value).isEqualTo(1.0)
        assertThat(first.series["ai"]!!.single().value).isEqualTo(2.0)
        assertThat(requests.single().metricDataQueries()).hasSize(2)
        clock.at = clock.at.plusSeconds(299); assertThat(client.fetch(queries, from, until)).isSameAs(first)
        clock.at = clock.at.plusSeconds(1); client.fetch(queries, from, until)
        client.fetch(queries, from.minusSeconds(3600), until)
        assertThat(requests).hasSize(3)
    }
    @Test fun `페이지 토큰을 이어 받고 시각순으로 합치며 실패 값은 캐시하지 않는다`() {
        `when`(sdk.getMetricData(any(GetMetricDataRequest::class.java)))
            .thenReturn(GetMetricDataResponse.builder().metricDataResults(result("server", 1.0, StatusCode.PARTIAL_DATA)).nextToken("next").build())
            .thenReturn(GetMetricDataResponse.builder().metricDataResults(result("server", 1.0)).build())
        assertThat(client.fetch(listOf(query("server")), from, until).series["server"]).hasSize(1)
        clock.at = clock.at.plusSeconds(300)
        `when`(sdk.getMetricData(any(GetMetricDataRequest::class.java))).thenThrow(IllegalStateException("credentials and private details"))
        assertThatThrownBy { client.fetch(listOf(query("server")), from, until) }.isInstanceOf(InfraMetricsUnavailable::class.java).hasMessage("인프라 지표를 가져올 수 없습니다.")
        `when`(sdk.getMetricData(any(GetMetricDataRequest::class.java))).thenReturn(GetMetricDataResponse.builder().metricDataResults(result("server")).build())
        assertThat(client.fetch(listOf(query("server")), from, until).series["server"]).hasSize(1)
    }
    @Test fun `최종 부분 응답과 누락 ID 및 잘못된 값은 준비 중 수치로 위장하지 않는다`() {
        for (response in listOf(
            GetMetricDataResponse.builder().metricDataResults(result("server", status = StatusCode.PARTIAL_DATA)).build(),
            GetMetricDataResponse.builder().metricDataResults(result("other")).build(),
            GetMetricDataResponse.builder().metricDataResults(result("server", Double.NaN)).build())) {
            `when`(sdk.getMetricData(any(GetMetricDataRequest::class.java))).thenReturn(response)
            assertThatThrownBy { client.fetch(listOf(query("server")), from, until) }.isInstanceOf(InfraMetricsUnavailable::class.java)
        }
    }
}
