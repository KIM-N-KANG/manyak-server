package com.knk.manyak.admin.infra

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class InfraMetricCatalogTests {
    @Test fun `운영 리소스별 원 지표와 CloudWatch namespace 차원 통계량을 만든다`() {
        val props = InfraMetricsProperties(services = mapOf("server" to "manyak-prod", "ai" to "manyak-prod-ai", "notification" to "manyak-prod-notification"),
            loadBalancer = "app/manyak-prod-alb/test", cacheClusters = listOf("manyak-prod-redis", "manyak-prod-redis-rg-001"), accountId = "000000000000")
        val queries = InfraMetricCatalog(props).queries()
        assertThat(queries).hasSize(25)
        assertThat(queries.map { it.id() }).doesNotHaveDuplicates()
        assertThat(queries).allMatch { it.metricStat().period() == 300 && it.returnData() }
        val running = queries.filter { it.metricStat().metric().metricName() == "RunningTaskCount" }
        assertThat(running).hasSize(3).allMatch { it.metricStat().metric().namespace() == "ECS/ContainerInsights" }
        assertThat(running.flatMap { it.metricStat().metric().dimensions() }.filter { it.name() == "ServiceName" }.map { it.value() })
            .containsExactlyInAnyOrderElementsOf(props.services.values)
        val p95 = queries.single { it.metricStat().stat() == "p95" }
        assertThat(p95.metricStat().metric().metricName()).isEqualTo("TargetResponseTime")
        assertThat(queries.filter { it.metricStat().metric().namespace() == "AWS/ElastiCache" }).hasSize(6)
        assertThat(queries.filter { it.metricStat().metric().namespace() == "AWS/ES" }).hasSize(2).allMatch {
            it.metricStat().metric().dimensions().associate { d -> d.name() to d.value() } == mapOf("DomainName" to "manyak-logs", "ClientId" to "000000000000") }
    }
}
