package com.knk.manyak.admin.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import java.time.*

@ConfigurationProperties("manyak.admin.infra")
class InfraMetricsProperties(val region: String = "ap-northeast-2", val cacheSeconds: Long = 300,
    val timeoutSeconds: Long = 10, val cluster: String = "manyak-prod", val services: Map<String, String> = emptyMap(),
    val loadBalancer: String = "", val database: String = "manyak-prod-pg", val cacheClusters: List<String> = emptyList(),
    val domain: String = "manyak-logs", val accountId: String = "", val cacheNodeId: String = "0001")

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InfraMetricsProperties::class)
class InfraMetricsConfiguration {
    @Bean(destroyMethod = "close") fun infraCloudWatchClient(properties: InfraMetricsProperties): CloudWatchClient = CloudWatchClient.builder()
        .region(Region.of(properties.region))
        // 기본 자격증명 체인이 ECS 컨테이너 자격증명(태스크 역할)을 사용한다. 정적 키는 설정하지 않는다.
        .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(properties.timeoutSeconds))
            .apiCallAttemptTimeout(Duration.ofSeconds(minOf(5, properties.timeoutSeconds))).build()).build()
}
class InfraMetricsUnavailable : RuntimeException("인프라 지표를 가져올 수 없습니다.")
data class InfraMetricPoint(val at: Instant, val value: Double)
data class InfraMetricSnapshot(val fetchedAt: Instant, val series: Map<String, List<InfraMetricPoint>>)

/** API 계약과 독립적인 조회 단위. 여러 MetricDataQueries를 한 요청에 보내고 원값만 반환한다. */
@Component
class CloudWatchInfraClient(private val client: CloudWatchClient, private val properties: InfraMetricsProperties,
    private val clock: Clock = Clock.systemUTC()) {
    private data class Key(val queries: List<MetricDataQuery>, val from: Instant, val until: Instant)
    private val cache = linkedMapOf<Key, InfraMetricSnapshot>()
    @Synchronized fun fetch(queries: List<MetricDataQuery>, from: Instant, until: Instant): InfraMetricSnapshot {
        require(queries.size in 1..500 && queries.map { it.id() }.distinct().size == queries.size && from < until)
        val key = Key(queries.sortedBy { it.id() }, from, until)
        cache[key]?.takeIf { clock.instant().isBefore(it.fetchedAt.plusSeconds(properties.cacheSeconds)) }?.let { return it }
        try {
            val points = queries.associate { it.id() to sortedMapOf<Instant, Double>() }
            val statuses = mutableMapOf<String, StatusCode>()
            val tokens = mutableSetOf<String>()
            var token: String? = null
            val deadline = System.nanoTime() + Duration.ofSeconds(properties.timeoutSeconds).toNanos()
            do {
                val remaining = deadline - System.nanoTime(); check(remaining > 0)
                val response = client.getMetricData(GetMetricDataRequest.builder().metricDataQueries(key.queries)
                    .startTime(from).endTime(until).scanBy(ScanBy.TIMESTAMP_ASCENDING).nextToken(token)
                    .overrideConfiguration { it.apiCallTimeout(Duration.ofNanos(remaining)) }.build())
                check(response.messages().isEmpty())
                response.metricDataResults().forEach { series ->
                    val values = requireNotNull(points[series.id()])
                    check(series.timestamps().size == series.values().size)
                    check(series.statusCode() in setOf(StatusCode.COMPLETE, StatusCode.PARTIAL_DATA))
                    statuses[series.id()] = series.statusCode()
                    series.timestamps().zip(series.values()).forEach { (at, value) ->
                        check(value.isFinite())
                        // CloudWatch가 시작 시각을 내림하므로 경계 바깥 점은 응답에서 제외한다.
                        if (at < from || at >= until) return@forEach
                        val old = values.put(at, value); check(old == null || old == value)
                    }
                }
                token = response.nextToken()?.takeIf { it.isNotBlank() }
                check(token == null || (tokens.add(token) && tokens.size <= 100))
            } while (token != null)
            check(statuses.keys == points.keys && statuses.values.all { it == StatusCode.COMPLETE })
            val result = InfraMetricSnapshot(clock.instant(), points.mapValues { (_, values) -> values.map { InfraMetricPoint(it.key, it.value) } })
            cache.entries.removeIf { !clock.instant().isBefore(it.value.fetchedAt.plusSeconds(properties.cacheSeconds)) }
            if (cache.size >= 32) cache.remove(cache.keys.first())
            cache[key] = result
            return result
        } catch (_: Exception) { throw InfraMetricsUnavailable() }
    }
}
