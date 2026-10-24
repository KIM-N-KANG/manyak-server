package com.knk.manyak.admin.infra

import org.springframework.stereotype.Component
import software.amazon.awssdk.services.cloudwatch.model.*

/** AWS 원 지표 정의만 보유한다. 통계와 차원은 ADMIN 4-1-21·4-2-33을 따른다. */
@Component
class InfraMetricCatalog(private val properties: InfraMetricsProperties) {
    fun queries(periodSeconds: Int = 300): List<MetricDataQuery> {
        require(periodSeconds >= 60 && periodSeconds % 60 == 0)
        val result = mutableListOf<MetricDataQuery>()
        fun add(id: String, namespace: String, name: String, dimensions: Map<String, String>, stat: String = "Average") {
            result += MetricDataQuery.builder().id(id).returnData(true).metricStat(MetricStat.builder().period(periodSeconds).stat(stat)
                .metric(Metric.builder().namespace(namespace).metricName(name).dimensions(dimensions.toSortedMap().map {
                    Dimension.builder().name(it.key).value(it.value).build() }).build()).build()).build()
        }
        properties.servicePairs().forEachIndexed { index, (_, service) ->
            val dimensions = mapOf("ClusterName" to properties.cluster, "ServiceName" to service)
            add("ecs_${index}_cpu", "AWS/ECS", "CPUUtilization", dimensions)
            add("ecs_${index}_memory", "AWS/ECS", "MemoryUtilization", dimensions)
            add("ecs_${index}_cpu_max", "AWS/ECS", "CPUUtilization", dimensions, "Maximum")
            add("ecs_${index}_memory_max", "AWS/ECS", "MemoryUtilization", dimensions, "Maximum")
        }
        if (properties.loadBalancer.isNotBlank()) {
            val alb = mapOf("LoadBalancer" to properties.loadBalancer)
            add("alb_requests", "AWS/ApplicationELB", "RequestCount", alb, "Sum")
            add("alb_elb_5xx", "AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", alb, "Sum")
            add("alb_target_5xx", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", alb, "Sum")
            add("alb_response_p95", "AWS/ApplicationELB", "TargetResponseTime", alb, "p95")
        }
        if (properties.database.isNotBlank()) {
            val db = mapOf("DBInstanceIdentifier" to properties.database)
            add("rds_cpu", "AWS/RDS", "CPUUtilization", db)
            add("rds_storage", "AWS/RDS", "FreeStorageSpace", db, "Minimum")
            add("rds_connections", "AWS/RDS", "DatabaseConnections", db, "Maximum")
            add("rds_read_latency", "AWS/RDS", "ReadLatency", db)
            add("rds_write_latency", "AWS/RDS", "WriteLatency", db)
        }
        properties.nodes().forEachIndexed { index, cluster ->
            val node = mapOf("CacheClusterId" to cluster)
            add("cache_${index}_cpu", "AWS/ElastiCache", "EngineCPUUtilization", node)
            add("cache_${index}_memory", "AWS/ElastiCache", "DatabaseMemoryUsagePercentage", node)
            add("cache_${index}_connections", "AWS/ElastiCache", "CurrConnections", node, "Maximum")
        }
        if (properties.hasSearch()) {
            val domain = mapOf("DomainName" to properties.domain, "ClientId" to properties.accountId)
            add("search_storage", "AWS/ES", "FreeStorageSpace", domain, "Minimum")
            add("search_jvm", "AWS/ES", "JVMMemoryPressure", domain, "Maximum")
        }
        return result
    }
}
