package com.knk.manyak.admin.infra

import org.springframework.stereotype.Component
import software.amazon.awssdk.services.cloudwatch.model.*

/** AWS 원 지표 정의만 보유한다. 화면 카드·기간·응답 형태는 정본 확정 후 연결한다. */
@Component
class InfraMetricCatalog(private val properties: InfraMetricsProperties) {
    fun queries(periodSeconds: Int = 300): List<MetricDataQuery> {
        require(periodSeconds >= 60 && periodSeconds % 60 == 0)
        if (properties.loadBalancer.isBlank() || properties.accountId.isBlank()) throw InfraMetricsUnavailable()
        val result = mutableListOf<MetricDataQuery>()
        fun add(id: String, namespace: String, name: String, dimensions: Map<String, String>, stat: String = "Average") {
            result += MetricDataQuery.builder().id(id).returnData(true).metricStat(MetricStat.builder().period(periodSeconds).stat(stat)
                .metric(Metric.builder().namespace(namespace).metricName(name).dimensions(dimensions.toSortedMap().map {
                    Dimension.builder().name(it.key).value(it.value).build() }).build()).build()).build()
        }
        properties.services.forEach { (key, service) ->
            require(key.matches(Regex("[a-z][a-z0-9_]*")))
            val dimensions = mapOf("ClusterName" to properties.cluster, "ServiceName" to service)
            add("${key}_cpu", "AWS/ECS", "CPUUtilization", dimensions)
            add("${key}_memory", "AWS/ECS", "MemoryUtilization", dimensions)
            add("${key}_running", "ECS/ContainerInsights", "RunningTaskCount", dimensions)
        }
        val alb = mapOf("LoadBalancer" to properties.loadBalancer)
        add("alb_requests", "AWS/ApplicationELB", "RequestCount", alb, "Sum")
        add("alb_target_5xx", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", alb, "Sum")
        add("alb_response_p95", "AWS/ApplicationELB", "TargetResponseTime", alb, "p95")
        val db = mapOf("DBInstanceIdentifier" to properties.database)
        add("rds_cpu", "AWS/RDS", "CPUUtilization", db)
        add("rds_storage", "AWS/RDS", "FreeStorageSpace", db, "Minimum")
        add("rds_connections", "AWS/RDS", "DatabaseConnections", db)
        add("rds_read_latency", "AWS/RDS", "ReadLatency", db)
        add("rds_write_latency", "AWS/RDS", "WriteLatency", db)
        properties.cacheClusters.forEachIndexed { index, cluster ->
            val node = mapOf("CacheClusterId" to cluster, "CacheNodeId" to properties.cacheNodeId)
            add("cache_${index}_cpu", "AWS/ElastiCache", "CPUUtilization", node)
            add("cache_${index}_memory", "AWS/ElastiCache", "DatabaseMemoryUsagePercentage", node)
            add("cache_${index}_connections", "AWS/ElastiCache", "CurrConnections", node)
        }
        val domain = mapOf("DomainName" to properties.domain, "ClientId" to properties.accountId)
        add("search_storage", "AWS/ES", "FreeStorageSpace", domain, "Minimum")
        add("search_jvm", "AWS/ES", "JVMMemoryPressure", domain, "Maximum")
        return result
    }
}
