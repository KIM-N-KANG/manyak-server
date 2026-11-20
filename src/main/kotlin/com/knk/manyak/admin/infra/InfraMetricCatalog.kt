package com.knk.manyak.admin.infra

import org.springframework.stereotype.Component
import software.amazon.awssdk.services.cloudwatch.model.*

/** CloudWatch 원 지표 정의만 보유한다. 지표, 통계, 차원은 ADMIN 4-2-34 표를 따른다. */
@Component
class InfraMetricCatalog(private val properties: InfraMetricsProperties) {
    fun queries(periodSeconds: Int = 300): List<MetricDataQuery> {
        require(periodSeconds >= 60 && periodSeconds % 60 == 0)
        val result = mutableListOf<MetricDataQuery>()
        fun add(id: String, namespace: String, name: String, dimension: Pair<String, String>, stat: String = "Average") {
            result += MetricDataQuery.builder().id(id).returnData(true).metricStat(MetricStat.builder().period(periodSeconds).stat(stat)
                .metric(Metric.builder().namespace(namespace).metricName(name)
                    .dimensions(Dimension.builder().name(dimension.first).value(dimension.second).build()).build()).build()).build()
        }
        if (properties.hostAsg.isNotBlank()) {
            // 오토스케일링 그룹 차원이라 인스턴스가 교체되어도 같은 계열로 이어진다.
            val host = "AutoScalingGroupName" to properties.hostAsg
            add("host_cpu", "AWS/EC2", "CPUUtilization", host)
            add("host_cpu_max", "AWS/EC2", "CPUUtilization", host, "Maximum")
            add("host_cpu_credit", "AWS/EC2", "CPUCreditBalance", host, "Minimum")
            add("host_status", "AWS/EC2", "StatusCheckFailed", host, "Maximum")
        }
        if (properties.database.isNotBlank()) {
            val db = "DBInstanceIdentifier" to properties.database
            add("rds_cpu", "AWS/RDS", "CPUUtilization", db)
            add("rds_storage", "AWS/RDS", "FreeStorageSpace", db, "Minimum")
            add("rds_connections", "AWS/RDS", "DatabaseConnections", db, "Maximum")
            add("rds_read_latency", "AWS/RDS", "ReadLatency", db)
            add("rds_write_latency", "AWS/RDS", "WriteLatency", db)
        }
        return result
    }
}
