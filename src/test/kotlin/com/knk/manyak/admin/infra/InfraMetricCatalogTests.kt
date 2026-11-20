package com.knk.manyak.admin.infra

import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test

class InfraMetricCatalogTests {
    @Test fun `앱 호스트 4개와 RDS 5개만 만들고 비어 있는 묶음은 질의하지 않는다`() {
        val queries = InfraMetricCatalog(InfraMetricsProperties(hostAsg = "manyak-prod-host", database = "private-rds")).queries(900)
        assertThat(queries).hasSize(9).allMatch { it.metricStat().period() == 900 && it.returnData() }
        assertThat(queries.map { it.id() }).doesNotHaveDuplicates()
        assertThat(queries.map { it.metricStat().metric().namespace() }.distinct()).containsExactlyInAnyOrder("AWS/EC2", "AWS/RDS")
        assertThat(queries.filter { it.metricStat().metric().namespace() == "AWS/EC2" }).hasSize(4).allMatch {
            it.metricStat().metric().dimensions().associate { d -> d.name() to d.value() } == mapOf("AutoScalingGroupName" to "manyak-prod-host") }
        assertThat(InfraMetricCatalog(InfraMetricsProperties(database = "private-rds")).queries()).hasSize(5)
        assertThat(InfraMetricCatalog(InfraMetricsProperties(hostAsg = "manyak-prod-host")).queries()).hasSize(4)
        assertThat(InfraMetricCatalog(InfraMetricsProperties()).queries()).isEmpty()
    }
}
