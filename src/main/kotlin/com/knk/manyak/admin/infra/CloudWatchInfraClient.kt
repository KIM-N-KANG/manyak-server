package com.knk.manyak.admin.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.*
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import software.amazon.awssdk.services.ecs.EcsClient
import java.time.*

@ConfigurationProperties("manyak.admin.infra")
class InfraMetricsProperties(val region: String = "ap-northeast-2", val cacheSeconds: Long = 60,
    val timeoutSeconds: Long = 5, val cooldownSeconds: Long = 60, val databaseCacheSeconds: Long = 300,
    val cacheKeys: Int = 100, val maxRangeDays: Long = 31, val shortRangeDays: Long = 2,
    val mediumRangeDays: Long = 7, val fineRetentionDays: Long = 63, val databaseMaxLimit: Int = 100, val cluster: String = "", val services: String = "", val loadBalancer: String = "",
    val database: String = "", val cacheClusters: String = "", val domain: String = "", val accountId: String = "") {
    fun servicePairs(): List<Pair<String, String>> {
        if (cluster.isBlank() || services.isBlank()) return emptyList()
        val pairs = services.split(',').map { entry ->
            val parts = entry.trim().split('=', limit = 2)
            if (parts.size != 2 || parts.any { it.isBlank() }) throw InfraFetchFailure("not_configured", null)
            parts[0].trim() to parts[1].trim()
        }
        if (pairs.size > 10 || pairs.map { it.first }.distinct().size != pairs.size) throw InfraFetchFailure("not_configured", null)
        return pairs
    }
    fun nodes() = cacheClusters.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun hasSearch() = domain.isNotBlank() && accountId.isNotBlank()
}
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InfraMetricsProperties::class)
class InfraMetricsConfiguration {
    private fun clientConfiguration(p: InfraMetricsProperties) = ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(p.timeoutSeconds)).apiCallAttemptTimeout(Duration.ofSeconds(p.timeoutSeconds)).build()
    // 정적 키 없이 SDK 기본 체인의 태스크 역할 자격증명을 사용한다. 생성 시 AWS 호출은 하지 않는다.
    @Bean(destroyMethod = "close") fun infraCloudWatchClient(p: InfraMetricsProperties): CloudWatchClient = CloudWatchClient.builder()
        .region(Region.of(p.region)).overrideConfiguration(clientConfiguration(p)).build()
    @Bean(destroyMethod = "close") fun infraEcsClient(p: InfraMetricsProperties): EcsClient = EcsClient.builder()
        .region(Region.of(p.region)).overrideConfiguration(clientConfiguration(p)).build()
}
internal fun remaining(deadline: Long, service: String): Duration {
    val nanos = deadline - System.nanoTime()
    if (nanos <= 0) throw InfraFetchFailure("timeout", service)
    return Duration.ofNanos(nanos)
}
internal fun <T> infraCall(service: String, action: () -> T): T = try { action() }
catch (f: InfraFetchFailure) { throw f }
catch (f: Exception) {
    if (f is InterruptedException) Thread.currentThread().interrupt()
    val aws = f as? AwsServiceException
    val code = aws?.awsErrorDetails()?.errorCode()?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }
    val reason = when {
        f is ApiCallTimeoutException || f is ApiCallAttemptTimeoutException || f is java.util.concurrent.TimeoutException || f is InterruptedException -> "timeout"
        aws?.statusCode() == 403 || code?.contains("AccessDenied", true) == true -> "access_denied"
        aws?.statusCode() == 429 || code?.contains("Throttl", true) == true || code == "TooManyRequestsException" -> "throttled"
        else -> "aws_error"
    }
    throw InfraFetchFailure(reason, service, code)
}
/** 캐시는 응답 서비스에만 둔다. 모든 페이지가 같은 전체 시간 한도를 공유한다. */
@Component
class CloudWatchInfraClient(private val client: CloudWatchClient, private val properties: InfraMetricsProperties) {
    private fun incomplete(code: String?): Nothing = throw InfraFetchFailure("incomplete", "cloudwatch",
        code?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) })

    fun fetch(queries: List<MetricDataQuery>, from: Instant, until: Instant,
        deadline: Long = System.nanoTime() + Duration.ofSeconds(properties.timeoutSeconds).toNanos()): Map<String, List<InfraMetricPoint>> = infraCall("cloudwatch") {
        if (queries.isEmpty() || from == until) return@infraCall queries.associate { it.id() to emptyList() }
        require(queries.size <= 500 && queries.map { it.id() }.distinct().size == queries.size && from < until)
        val points = queries.associate { it.id() to sortedMapOf<Instant, Double>() }
        val statuses = mutableMapOf<String, StatusCode>(); val tokens = mutableSetOf<String>(); var token: String? = null
        do {
            val response = client.getMetricData(GetMetricDataRequest.builder().metricDataQueries(queries).startTime(from).endTime(until)
                .scanBy(ScanBy.TIMESTAMP_ASCENDING).nextToken(token).overrideConfiguration { it.apiCallTimeout(remaining(deadline, "cloudwatch")) }.build())
            remaining(deadline, "cloudwatch")
            if (response.messages().isNotEmpty()) incomplete(response.messages().first().code())
            response.metricDataResults().forEach { series ->
                val values = requireNotNull(points[series.id()]); check(series.timestamps().size == series.values().size)
                if (series.messages().isNotEmpty()) incomplete(series.messages().first().code())
                if (series.statusCode() !in setOf(StatusCode.COMPLETE, StatusCode.PARTIAL_DATA)) incomplete(series.statusCodeAsString())
                statuses[series.id()] = series.statusCode()
                series.timestamps().zip(series.values()).forEach point@ { (at, value) ->
                    check(value.isFinite()); if (at < from || at >= until) return@point
                    val old = values.put(at, value); check(old == null || old == value)
                }
            }
            token = response.nextToken()?.takeIf { it.isNotBlank() }
            check(token == null || (tokens.add(token) && tokens.size <= 100))
        } while (token != null)
        if (statuses.keys != points.keys) incomplete(null)
        statuses.values.firstOrNull { it != StatusCode.COMPLETE }?.let { incomplete(it.toString()) }
        points.mapValues { (_, values) -> values.map { InfraMetricPoint(it.key, it.value) } }
    }
}
