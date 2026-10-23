package com.knk.manyak.admin.infra

import com.knk.manyak.admin.dto.AdminDateRange
import com.knk.manyak.admin.dto.badAdminQuery
import java.time.*
import java.time.temporal.ChronoUnit

class InfraMetricsQuery {
    var from: String? = null
    var to: String? = null
    fun resolve(clock: Clock, properties: InfraMetricsProperties = InfraMetricsProperties()): InfraRange {
        val now = clock.instant(); val today = now.atZone(AdminDateRange.KST).toLocalDate()
        if ((from == null) != (to == null)) badAdminQuery("from과 to는 함께 지정해야 합니다.")
        AdminDateRange.of(from, to)
        val start = from?.let(LocalDate::parse) ?: today; val end = to?.let(LocalDate::parse) ?: today
        val days = ChronoUnit.DAYS.between(start, end) + 1
        if (end > today || days !in 1..properties.maxRangeDays) badAdminQuery("기간은 오늘까지 1~31일이어야 합니다.")
        val since = start.atStartOfDay(AdminDateRange.KST).toInstant()
        val until = minOf(now, end.plusDays(1).atStartOfDay(AdminDateRange.KST).toInstant())
        val period = if (since < now.minus(Duration.ofDays(properties.fineRetentionDays)) || days > properties.mediumRangeDays) 3600 else if (days > properties.shortRangeDays) 900 else 300
        return InfraRange(start, end, since, until, period)
    }
}
data class InfraRange(val from: LocalDate, val to: LocalDate, val since: Instant, val until: Instant, val periodSeconds: Int)
data class InfraMetricPoint(val at: Instant, val value: Double)
data class InfraServiceMetrics(val name: String, val runningTasks: Int, val desiredTasks: Int, val pendingTasks: Int,
    val cpu: List<InfraMetricPoint>, val memory: List<InfraMetricPoint>)
data class InfraEcsMetrics(val services: List<InfraServiceMetrics>)
data class InfraAlbMetrics(val requests: List<InfraMetricPoint>, val elb5xx: List<InfraMetricPoint>, val target5xx: List<InfraMetricPoint>, val responseTimeP95Ms: List<InfraMetricPoint>)
data class InfraRdsMetrics(val cpu: List<InfraMetricPoint>, val freeStorageBytes: List<InfraMetricPoint>, val connections: List<InfraMetricPoint>, val readLatencyMs: List<InfraMetricPoint>, val writeLatencyMs: List<InfraMetricPoint>)
data class InfraRedisNode(val id: String, val cpu: List<InfraMetricPoint>, val memory: List<InfraMetricPoint>, val connections: List<InfraMetricPoint>)
data class InfraRedisMetrics(val nodes: List<InfraRedisNode>)
data class InfraSearchMetrics(val freeStorageBytes: List<InfraMetricPoint>, val jvmMemoryPressure: List<InfraMetricPoint>)
data class InfraMetricsResponse(val asOf: Instant, val from: LocalDate, val to: LocalDate, val periodSeconds: Int,
    val ecs: InfraEcsMetrics?, val alb: InfraAlbMetrics?, val rds: InfraRdsMetrics?, val redis: InfraRedisMetrics?, val opensearch: InfraSearchMetrics?)
data class InfraTableSize(val schema: String, val name: String, val totalBytes: Long, val tableBytes: Long, val indexBytes: Long, val estimatedRows: Long)
data class InfraDatabaseSnapshot(val databaseBytes: Long, val tableCount: Long, val tables: List<InfraTableSize>)
data class InfraDatabaseResponse(val asOf: Instant, val databaseBytes: Long, val tableCount: Long, val tables: List<InfraTableSize>)
class InfraMetricsUnavailable : RuntimeException("인프라 지표를 가져올 수 없습니다.")
class InfraFetchFailure(val reason: String, val service: String?, val errorCode: String? = null) : RuntimeException()
