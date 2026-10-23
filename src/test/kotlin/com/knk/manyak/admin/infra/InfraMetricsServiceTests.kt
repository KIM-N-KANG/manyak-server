package com.knk.manyak.admin.infra

import com.knk.manyak.admin.analytics.MutableAnalyticsClock
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.*
import java.time.*
import java.util.concurrent.*

internal fun infraFullProperties(timeout: Long = 5) = InfraMetricsProperties(timeoutSeconds = timeout, cluster = "private-cluster", services = "server=private-server,ai=private-ai,notification=private-notification",
    loadBalancer = "app/private-alb/secret", database = "private-db", cacheClusters = "redis-001,redis-002", domain = "private-domain", accountId = "123456789012")
internal class InfraFixture(val properties: InfraMetricsProperties = infraFullProperties()) : AutoCloseable {
    val clock = MutableAnalyticsClock(Instant.parse("2026-10-22T17:00:00Z")) // 10/23 02:00 KST
    val cw = mock(CloudWatchClient::class.java); val ecs = mock(EcsClient::class.java)
    val requests = mutableListOf<GetMetricDataRequest>(); val ecsRequests = mutableListOf<DescribeServicesRequest>()
    val catalog = InfraMetricCatalog(properties)
    val service = InfraMetricsService(InfraAwsGateway(CloudWatchInfraClient(cw, properties), ecs, catalog, properties), catalog, properties, clock)
    init {
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument<GetMetricDataRequest>(0); synchronized(requests) { requests.add(request) }
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { query ->
                val name = query.metricStat().metric().metricName()
                val value = when (name) { "TargetResponseTime" -> 0.123; "ReadLatency" -> 0.0008; "WriteLatency" -> 0.0016; "FreeStorageSpace" -> 1024.0; else -> 10.0 }
                val dates = if (name == "RequestCount") listOf(request.startTime(), request.startTime().plusSeconds(600), request.startTime().plusSeconds(1200)).filter { it < request.endTime() } else listOf(request.startTime())
                MetricDataResult.builder().id(query.id()).statusCode(StatusCode.COMPLETE).timestamps(dates).values(List(dates.size) { value }).build()
            }).build()
        }
        `when`(ecs.describeServices(any(DescribeServicesRequest::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument<DescribeServicesRequest>(0); synchronized(ecsRequests) { ecsRequests.add(request) }
            DescribeServicesResponse.builder().services(request.services().map { name -> software.amazon.awssdk.services.ecs.model.Service.builder().serviceName(name).runningCount(2).desiredCount(3).pendingCount(1).build() }).build()
        }
    }
    override fun close() { service.close() }
}

class InfraMetricsServiceTests {
    @Test fun `기본 KST 범위와 23개 질의 및 태스크 현재값과 변환과 건수만 0 채움을 조립한다`(): Unit = InfraFixture().use { f ->
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(result.from.toString()).isEqualTo("2026-10-23"); assertThat(result.to).isEqualTo(result.from)
        assertThat(result.periodSeconds).isEqualTo(300)
        assertThat(f.requests.single().startTime()).isEqualTo(Instant.parse("2026-10-22T15:00:00Z"))
        assertThat(f.requests.single().endTime()).isEqualTo(f.clock.instant())
        assertThat(f.requests.single().metricDataQueries()).hasSize(23)
        assertThat(f.ecsRequests.single().services()).containsExactly("private-server", "private-ai", "private-notification")
        assertThat(result.ecs!!.services.map { it.name }).containsExactly("server", "ai", "notification")
        assertThat(result.ecs.services.first().runningTasks).isEqualTo(2); assertThat(result.ecs.services.first().desiredTasks).isEqualTo(3); assertThat(result.ecs.services.first().pendingTasks).isEqualTo(1)
        assertThat(result.ecs.services.first().cpu).hasSize(1)
        assertThat(result.alb!!.requests).hasSize(24); assertThat(result.alb.requests.count { it.value == 0.0 }).isEqualTo(21)
        assertThat(result.alb.responseTimeP95Ms.single().value).isEqualTo(123.0)
        assertThat(result.rds!!.readLatencyMs.single().value).isEqualTo(0.8)
        assertThat(result.opensearch!!.freeStorageBytes.single().value).isEqualTo(1073741824.0)
    }
    @Test fun `KST 종료일과 집계 간격은 2 3 7 8 31일 및 63일 보관 경계에 맞는다`(): Unit = InfraFixture().use { f ->
        f.clock.at = Instant.parse("2026-10-23T00:00:00Z")
        val today = LocalDate.parse("2026-10-23")
        for ((days, expected) in listOf(2 to 300, 3 to 900, 7 to 900, 8 to 3600, 31 to 3600)) {
            val q = InfraMetricsQuery().apply { from = today.minusDays(days - 1L).toString(); to = today.toString() }
            assertThat(q.resolve(f.clock).periodSeconds).isEqualTo(expected)
        }
        val past = InfraMetricsQuery().apply { from = "2026-10-20"; to = "2026-10-21" }.resolve(f.clock)
        assertThat(past.until).isEqualTo(Instant.parse("2026-10-21T15:00:00Z"))
        val old = InfraMetricsQuery().apply { from = today.minusDays(64).toString(); to = today.minusDays(63).toString() }
        assertThat(old.resolve(f.clock).periodSeconds).isEqualTo(3600)
        for ((from, to) in listOf("2026-09-22" to "2026-10-23", "2026-10-23" to "2026-10-24", "2026-10-23" to null, "2026-10-23" to "2026-10-22", "2026-02-30" to "2026-03-01", "bad" to "bad")) {
            assertThatThrownBy { f.service.metrics(InfraMetricsQuery().apply { this.from = from; this.to = to }) }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
        }
        verifyNoInteractions(f.cw, f.ecs)
    }
    @Test fun `빈 묶음은 질의하지 않고 null로 두며 설정이 모두 비면 60초마다 한 번만 실패 로그를 남긴다`() {
        InfraFixture(InfraMetricsProperties(loadBalancer = "alb-only", domain = "domain-without-account")).use { f ->
            val result = f.service.metrics(InfraMetricsQuery())
            assertThat(result.alb).isNotNull(); assertThat(result.ecs).isNull(); assertThat(result.rds).isNull(); assertThat(result.redis).isNull(); assertThat(result.opensearch).isNull()
            assertThat(f.requests.single().metricDataQueries()).hasSize(4); verifyNoInteractions(f.ecs)
        }
        val logger = org.slf4j.LoggerFactory.getLogger(InfraMetricsService::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }; logger.addAppender(appender)
        try { InfraFixture(InfraMetricsProperties()).use { f ->
            repeat(2) { assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java) }
            verifyNoInteractions(f.cw, f.ecs)
            assertThat(appender.list.map { it.formattedMessage }).containsExactly("admin_infra_metrics_fetch_failed reason=not_configured service=null error_code=null")
        } } finally { logger.detachAppender(appender); appender.stop() }
    }
    @Test fun `60초 캐시 만료 실패는 이전 응답을 쓰며 60초 쿨다운 뒤 같은 키를 다시 갱신한다`(): Unit = InfraFixture().use { f ->
        val first = f.service.metrics(InfraMetricsQuery())
        f.clock.at = f.clock.at.plusSeconds(59); assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        f.clock.at = f.clock.at.plusSeconds(2)
        `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenThrow(CloudWatchException.builder().statusCode(500).message("secret failure").build())
        assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        f.clock.at = f.clock.at.plusSeconds(30); assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        verify(f.cw, times(2)).getMetricData(any(GetMetricDataRequest::class.java))
        f.clock.at = f.clock.at.plusSeconds(30); assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        verify(f.cw, times(3)).getMetricData(any(GetMetricDataRequest::class.java))
        val other = InfraMetricsQuery().apply { from = "2026-10-21"; to = "2026-10-21" }
        assertThatThrownBy { f.service.metrics(other) }.isInstanceOf(InfraMetricsUnavailable::class.java)
        verify(f.cw, times(4)).getMetricData(any(GetMetricDataRequest::class.java))
    }
    @Test fun `권한 거부와 제한 및 ECS 실패 항목은 원문 없이 이유와 서비스만 기록한다`() {
        val logger = org.slf4j.LoggerFactory.getLogger(InfraMetricsService::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }; logger.addAppender(appender)
        try {
            for ((code, reason) in listOf("AccessDeniedException" to "access_denied", "ThrottlingException" to "throttled")) InfraFixture().use { f ->
                `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenThrow(CloudWatchException.builder().statusCode(400).awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("SECRET").build()).build())
                assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
                assertThat(appender.list.last().formattedMessage).contains("reason=$reason", "service=cloudwatch", "error_code=$code").doesNotContain("SECRET")
            }
            InfraFixture().use { f ->
                `when`(f.ecs.describeServices(any(DescribeServicesRequest::class.java))).thenReturn(DescribeServicesResponse.builder().failures(Failure.builder().arn("private-ai").reason("SECRET").build()).build())
                assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
                assertThat(appender.list.last().formattedMessage).contains("reason=aws_error", "service=ecs").doesNotContain("SECRET", "private-ai")
            }
        } finally { logger.detachAppender(appender); appender.stop() }
    }
    @Test fun `갱신은 키마다 하나뿐이며 동시 요청은 기다리지 않고 사용 불가 또는 만료 값을 받는다`(): Unit = InfraFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val normal = f.cw.getMetricData(GetMetricDataRequest.builder().metricDataQueries(f.catalog.queries()).startTime(Instant.parse("2026-10-22T15:00:00Z")).endTime(f.clock.instant()).build())
        `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { entered.countDown(); release.await(3, TimeUnit.SECONDS); normal }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<InfraMetricsResponse> { f.service.metrics(InfraMetricsQuery()) }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
            release.countDown(); val first = running.get(3, TimeUnit.SECONDS)
            f.clock.at = f.clock.at.plusSeconds(61)
            val entered2 = CountDownLatch(1); val release2 = CountDownLatch(1)
            `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { entered2.countDown(); release2.await(3, TimeUnit.SECONDS); normal }
            val update = pool.submit<InfraMetricsResponse> { f.service.metrics(InfraMetricsQuery()) }
            assertThat(entered2.await(2, TimeUnit.SECONDS)).isTrue(); assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
            release2.countDown(); update.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `최근 100개 범위만 보관하고 접근한 키를 최근으로 갱신한다`(): Unit = InfraFixture(InfraMetricsProperties(loadBalancer = "alb")).use { f ->
        fun q(offset: Long) = InfraMetricsQuery().apply { from = LocalDate.parse("2026-10-23").minusDays(offset).toString(); to = from }
        (0L..99L).forEach { f.service.metrics(q(it)) }
        f.service.metrics(q(0)); f.service.metrics(q(100)); f.service.metrics(q(1))
        verify(f.cw, times(102)).getMetricData(any(GetMetricDataRequest::class.java))
    }
    @Test fun `AWS 두 호출은 합산 한도를 공유하고 기본 5초 무응답도 중단한다`() {
        InfraFixture(infraFullProperties(1)).use { f ->
            `when`(f.ecs.describeServices(any(DescribeServicesRequest::class.java))).thenAnswer { Thread.sleep(2000); DescribeServicesResponse.builder().build() }
            val start = System.nanoTime()
            assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(1800)
        }
        InfraFixture().use { f ->
            `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { Thread.sleep(10000); GetMetricDataResponse.builder().build() }
            val start = System.nanoTime()
            assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(4500L, 6500L)
        }
    }
    @Test fun `불완전 질의는 0 채움이나 캐시 갱신 없이 incomplete 로그와 쿨다운을 남긴다`() {
        val logger = org.slf4j.LoggerFactory.getLogger(InfraMetricsService::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }; logger.addAppender(appender)
        try {
            for (status in listOf(StatusCode.PARTIAL_DATA, StatusCode.INTERNAL_ERROR, StatusCode.FORBIDDEN, StatusCode.COMPLETE)) InfraFixture(InfraMetricsProperties(loadBalancer = "alb")).use { f ->
                `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { call ->
                    val request = call.getArgument<GetMetricDataRequest>(0)
                    GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { q ->
                        MetricDataResult.builder().id(q.id()).statusCode(if (q.id() == "alb_requests") status else StatusCode.COMPLETE)
                            .apply { if (status == StatusCode.COMPLETE && q.id() == "alb_requests") messages(MessageData.builder().code("BadQuery").value("PRIVATE ARN").build()) }.build()
                    }).build()
                }
                assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
                assertThat(appender.list.last().formattedMessage).contains("reason=incomplete", "service=cloudwatch", "error_code=" + if (status == StatusCode.COMPLETE) "BadQuery" else status.toString()).doesNotContain("PRIVATE ARN")
                f.clock.at = f.clock.at.plusSeconds(30)
                assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
                verify(f.cw, times(1)).getMetricData(any(GetMetricDataRequest::class.java))
            }
            for (status in listOf(StatusCode.FORBIDDEN, StatusCode.INTERNAL_ERROR)) InfraFixture(InfraMetricsProperties(loadBalancer = "alb")).use { f ->
                val first = f.service.metrics(InfraMetricsQuery()); f.clock.at = f.clock.at.plusSeconds(61)
                `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenReturn(GetMetricDataResponse.builder()
                    .metricDataResults(f.catalog.queries().map { MetricDataResult.builder().id(it.id()).statusCode(status).build() }).build())
                assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
                assertThat(appender.list.last().formattedMessage).contains("reason=incomplete", "error_code=$status")
            }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

}
