package com.knk.manyak.admin.infra

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class InfraMetricCatalogTests {
    @Test fun `운영 리소스별 원 지표와 CloudWatch namespace 차원 통계량을 만든다`() {
        val props = InfraMetricsProperties(cluster = "cluster-secret", services = "server=manyak-prod,ai=manyak-prod-ai,notification=manyak-prod-notification",
            loadBalancer = "app/manyak-prod-alb/test", cacheClusters = "manyak-prod-redis-rg-001,manyak-prod-redis-rg-002", database = "private-rds", domain = "manyak-logs", accountId = "000000000000")
        val queries = InfraMetricCatalog(props).queries()
        assertThat(queries).hasSize(23)
        assertThat(queries.map { it.id() }).doesNotHaveDuplicates()
        assertThat(queries).allMatch { it.metricStat().period() == 300 && it.returnData() }
        val running = queries.filter { it.metricStat().metric().metricName() == "RunningTaskCount" }
        assertThat(running).isEmpty()
        assertThat(queries.filter { it.id().endsWith("_cpu") && it.metricStat().metric().namespace() == "AWS/ECS" }.flatMap { it.metricStat().metric().dimensions() }.filter { it.name() == "ServiceName" }.map { it.value() })
            .containsExactlyInAnyOrderElementsOf(props.servicePairs().map { it.second })
        assertThat(queries.single { it.id() == "alb_elb_5xx" }.metricStat().metric().metricName()).isEqualTo("HTTPCode_ELB_5XX_Count")
        val redis = queries.filter { it.metricStat().metric().namespace() == "AWS/ElastiCache" }
        assertThat(redis).allMatch { it.metricStat().metric().dimensions().map { d -> d.name() } == listOf("CacheClusterId") }
        assertThat(redis.filter { it.id().endsWith("_cpu") }).allMatch { it.metricStat().metric().metricName() == "EngineCPUUtilization" }
        assertThat(queries.filter { it.id().endsWith("connections") }).allMatch { it.metricStat().stat() == "Maximum" }
        val p95 = queries.single { it.metricStat().stat() == "p95" }
        assertThat(p95.metricStat().metric().metricName()).isEqualTo("TargetResponseTime")
        assertThat(queries.filter { it.metricStat().metric().namespace() == "AWS/ElastiCache" }).hasSize(6)
        assertThat(queries.filter { it.metricStat().metric().namespace() == "AWS/ES" }).hasSize(2).allMatch {
            it.metricStat().metric().dimensions().associate { d -> d.name() to d.value() } == mapOf("DomainName" to "manyak-logs", "ClientId" to "000000000000") }
    }
}
