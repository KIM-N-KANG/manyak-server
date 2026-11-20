package com.knk.manyak.admin.infra

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.admin.analytics.MutableAnalyticsClock
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.slf4j.LoggerFactory
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.Base64
import java.util.concurrent.*

/** 운영과 같은 구성. 이름과 비밀값은 응답과 로그에 나오면 안 되는 표식이다. */
internal fun infraFullProperties(prometheusUrl: String, timeout: Long = 5) = InfraMetricsProperties(timeoutSeconds = timeout,
    hostAsg = "private-asg", database = "private-db", prometheusUrl = prometheusUrl, prometheusUsername = "private-reader",
    prometheusPassword = "private-password", prometheusAccessClientId = "private-client-id", prometheusAccessClientSecret = "private-client-secret")

/** ADMIN 5-5 홈서버 Prometheus 대역. 질의에 든 기록 규칙 계열로 응답을 고르고 요청을 기록한다. */
internal class PrometheusStub : Dispatcher() {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val bodies = ConcurrentHashMap<String, String>()
    @Volatile var failure: ((RecordedRequest) -> MockResponse?)? = null
    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        failure?.invoke(request)?.let { return it }
        val url = request.requestUrl!!
        val series = SERIES.first { url.queryParameter("query")!!.contains(it) }
        val body = bodies[series] ?: if (url.encodedPath.endsWith("query_range")) {
            matrix(mapOf<String, String>() to listOf(Instant.ofEpochSecond(url.queryParameter("start")!!.toDouble().toLong()) to DEFAULTS.getValue(series)))
        } else vector(mapOf<String, String>() to DEFAULTS.getValue(series))
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
    companion object {
        val SERIES = listOf("manyak_server:http_requests:rate3m", "manyak_server:http_5xx:rate3m", "manyak_server:http_latency_bucket_nostream:rate3m",
            "manyak_prod_host:memory_available_bytes", "manyak_prod_host:memory_total_bytes", "manyak_prod_host:disk_available_bytes",
            "manyak_prod_host:disk_total_bytes", "manyak_opensearch:disk_available_bytes", "manyak_opensearch:disk_total_bytes",
            "manyak_opensearch:jvm_memory_pressure_percent", "manyak_opensearch:cluster_status")
        val DEFAULTS = mapOf(SERIES[0] to "312", SERIES[1] to "1", SERIES[2] to "182", SERIES[3] to "1876000000", SERIES[4] to "4015000000",
            SERIES[5] to "15400000000", SERIES[6] to "21462000000", SERIES[7] to "114949550080", SERIES[8] to "137170518016", SERIES[9] to "9", SERIES[10] to "0")
        fun matrix(vararg series: Pair<Map<String, String>, List<Pair<Instant, String>>>) = """{"status":"success","data":{"resultType":"matrix","result":[""" +
            series.joinToString(",") { (labels, points) -> """{"metric":${labels(labels)},"values":[${points.joinToString(",") { "[${it.first.epochSecond},\"${it.second}\"]" }}]}""" } + "]}}"
        fun vector(vararg series: Pair<Map<String, String>, String>) = """{"status":"success","data":{"resultType":"vector","result":[""" +
            series.joinToString(",") { (labels, value) -> """{"metric":${labels(labels)},"value":[1795000000,"$value"]}""" } + "]}}"
        private fun labels(labels: Map<String, String>) = labels.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }
    }
}

internal class InfraFixture(configure: (String) -> InfraMetricsProperties = { infraFullProperties(it) }) : AutoCloseable {
    val clock = MutableAnalyticsClock(Instant.parse("2026-11-20T00:00:00Z")) // 2026-11-20 09:00 KST
    val prometheus = PrometheusStub()
    private val server = MockWebServer().apply { dispatcher = prometheus; start() }
    val properties = configure(server.url("/").toString().trimEnd('/'))
    val cw: CloudWatchClient = mock(CloudWatchClient::class.java)
    val redis: RedisInfraClient = mock(RedisInfraClient::class.java)
    val requests = CopyOnWriteArrayList<GetMetricDataRequest>()
    val catalog = InfraMetricCatalog(properties)
    val service = InfraMetricsService(CloudWatchInfraClient(cw, properties), PrometheusInfraClient(properties, ObjectMapper()), redis, catalog, properties, clock)
    init {
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument<GetMetricDataRequest>(0); requests += request
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { query ->
                val stat = query.metricStat()
                val value = when (stat.metric().metricName()) {
                    "CPUUtilization" -> if (stat.stat() == "Maximum") 12.3 else 4.1
                    "ReadLatency" -> 0.0008; "WriteLatency" -> 0.0016; "StatusCheckFailed" -> 0.0; else -> 10.0
                }
                MetricDataResult.builder().id(query.id()).statusCode(StatusCode.COMPLETE).timestamps(request.startTime()).values(value).build()
            }).build()
        }
        `when`(redis.fetch()).thenReturn(InfraRedisMetrics(2474639, 268435456, 6, 7450, true))
    }
    fun failCloudWatch(code: String) {
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenThrow(CloudWatchException.builder().statusCode(400)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("PRIVATE AWS MESSAGE").build()).build())
    }
    fun failAll() {
        failCloudWatch("InternalFailure")
        prometheus.failure = { MockResponse().setResponseCode(500) }
        `when`(redis.fetch()).thenThrow(IllegalStateException("PRIVATE REDIS MESSAGE"))
    }
    override fun close() { service.close(); server.shutdown() }
}

internal inline fun <T> infraLogs(block: (ListAppender<ILoggingEvent>) -> T): T {
    val logger = LoggerFactory.getLogger(InfraMetricsService::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }; logger.addAppender(appender)
    try { return block(appender) } finally { logger.detachAppender(appender); appender.stop() }
}

/** ADMIN 5-5-13 인프라 지표 행. 테스트 이름 앞의 따옴표 안이 스펙의 확인 항목이다. */
class InfraMetricsServiceTests {
    private val since = Instant.parse("2026-11-19T15:00:00Z") // 2026-11-20 00:00 KST
    private fun RecordedRequest.param(name: String) = requestUrl!!.queryParameter(name)

    @Test fun `'조회 구성' 질의 9개와 기록 규칙 계열만 읽는 Prometheus 질의와 Redis 한 번`(): Unit = InfraFixture().use { f ->
        f.service.metrics(InfraMetricsQuery())
        val queries = f.requests.single().metricDataQueries()
        assertThat(queries.map { q -> val s = q.metricStat(); listOf(s.metric().namespace(), s.metric().metricName(), s.stat(),
            s.metric().dimensions().joinToString { "${it.name()}=${it.value()}" }) }).containsExactlyInAnyOrder(
            listOf("AWS/EC2", "CPUUtilization", "Average", "AutoScalingGroupName=private-asg"),
            listOf("AWS/EC2", "CPUUtilization", "Maximum", "AutoScalingGroupName=private-asg"),
            listOf("AWS/EC2", "CPUCreditBalance", "Minimum", "AutoScalingGroupName=private-asg"),
            listOf("AWS/EC2", "StatusCheckFailed", "Maximum", "AutoScalingGroupName=private-asg"),
            listOf("AWS/RDS", "CPUUtilization", "Average", "DBInstanceIdentifier=private-db"),
            listOf("AWS/RDS", "FreeStorageSpace", "Minimum", "DBInstanceIdentifier=private-db"),
            listOf("AWS/RDS", "DatabaseConnections", "Maximum", "DBInstanceIdentifier=private-db"),
            listOf("AWS/RDS", "ReadLatency", "Average", "DBInstanceIdentifier=private-db"),
            listOf("AWS/RDS", "WriteLatency", "Average", "DBInstanceIdentifier=private-db"))
        assertThat(queries).allMatch { it.metricStat().period() == 300 && it.returnData() }
        val prom = f.prometheus.requests
        assertThat(prom).hasSize(11)
        val read = prom.flatMap { Regex("manyak_[a-z_]+:[a-z0-9_]+(:[a-z0-9]+)?").findAll(it.param("query")!!).map { m -> m.value }.toList() }.toSet()
        assertThat(read).containsExactlyInAnyOrderElementsOf(PrometheusStub.SERIES)
        assertThat(prom.map { Regex("[a-z_]+\\{|http_server_requests").containsMatchIn(it.param("query")!!) }).containsOnly(false)
        val ranges = prom.filter { it.requestUrl!!.encodedPath == "/api/v1/query_range" }
        assertThat(ranges).hasSize(7).allMatch { it.param("step") == "300" && it.param("start")!!.toDouble().toLong() == since.epochSecond + 300 }
        assertThat(prom.filter { it.requestUrl!!.encodedPath == "/api/v1/query" }).hasSize(4)
        verify(f.redis, times(1)).fetch()
    }

    @Test fun `'기본 기간' 쿼리 없이 KST 오늘 0시부터 지금까지 300초`(): Unit = InfraFixture().use { f ->
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(result.from).isEqualTo(LocalDate.parse("2026-11-20")); assertThat(result.to).isEqualTo(result.from)
        assertThat(result.periodSeconds).isEqualTo(300)
        assertThat(f.requests.single().startTime()).isEqualTo(since)
        assertThat(f.requests.single().endTime()).isEqualTo(Instant.parse("2026-11-20T00:00:00Z"))
    }

    @Test fun `'지난 날짜 끝' to 다음 날 KST 0시에 끝난다`(): Unit = InfraFixture().use { f ->
        f.service.metrics(InfraMetricsQuery().apply { from = "2026-11-17"; to = "2026-11-18" })
        assertThat(f.requests.single().endTime()).isEqualTo(Instant.parse("2026-11-18T15:00:00Z"))
    }

    @Test fun `'집계 간격'과 '오래된 시작' 2 3 7 8 31일과 64일 전 시작`(): Unit = InfraFixture().use { f ->
        val today = LocalDate.parse("2026-11-20")
        for ((days, expected) in listOf(2 to 300, 3 to 900, 7 to 900, 8 to 3600, 31 to 3600)) {
            val q = InfraMetricsQuery().apply { from = today.minusDays(days - 1L).toString(); to = today.toString() }
            assertThat(q.resolve(f.clock).periodSeconds).isEqualTo(expected)
        }
        val old = InfraMetricsQuery().apply { from = today.minusDays(64).toString(); to = today.minusDays(63).toString() }
        assertThat(old.resolve(f.clock).periodSeconds).isEqualTo(3600)
    }

    @Test fun `'기간 오류' 32일 내일 한쪽만 거꾸로는 400이고 대역을 부르지 않는다`(): Unit = InfraFixture().use { f ->
        for ((from, to) in listOf("2026-10-20" to "2026-11-20", "2026-11-20" to "2026-11-21", "2026-11-20" to null, null to "2026-11-20",
            "2026-11-20" to "2026-11-19", "bad" to "bad")) {
            assertThatThrownBy { f.service.metrics(InfraMetricsQuery().apply { this.from = from; this.to = to }) }
                .isInstanceOf(ResponseStatusException::class.java).hasMessageContaining("400")
        }
        verifyNoInteractions(f.cw, f.redis); assertThat(f.prometheus.requests).isEmpty()
    }

    @Test fun `'단위 변환' ReadLatency 0점0008초는 0점8밀리초`(): Unit = InfraFixture().use { f ->
        val rds = f.service.metrics(InfraMetricsQuery()).rds!!
        assertThat(rds.readLatencyMs.single().value).isCloseTo(0.8, within(1e-9))
        assertThat(rds.writeLatencyMs.single().value).isCloseTo(1.6, within(1e-9))
    }

    @Test fun `'빈 구간' 24구간 중 20구간만 오면 점 20개로 채우지 않는다`(): Unit = InfraFixture().use { f ->
        `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { query ->
                val times = if (query.id() == "host_cpu") (0L until 24L).filter { it % 6 != 5L }.map { request.startTime().plusSeconds(it * 300) } else emptyList()
                MetricDataResult.builder().id(query.id()).statusCode(StatusCode.COMPLETE).timestamps(times).values(times.map { 4.1 }).build()
            }).build()
        }
        val host = f.service.metrics(InfraMetricsQuery()).host!!
        assertThat(host.cpu).hasSize(20); assertThat(host.cpuMax).isEmpty()
    }

    @Test fun `'최댓값 통계' Average 4점1과 Maximum 12점3을 나눠 담는다`(): Unit = InfraFixture().use { f ->
        val host = f.service.metrics(InfraMetricsQuery()).host!!
        assertThat(host.cpu.single().value).isEqualTo(4.1); assertThat(host.cpuMax.single().value).isEqualTo(12.3)
        assertThat(host.cpu.single().at).isEqualTo(since)
    }

    @Test fun `'점의 시각' Prometheus 점 0시 5분은 구간 시작 0시`(): Unit = InfraFixture().use { f ->
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(result.requests!!.requests.single().at).isEqualTo(since)
        assertThat(result.hostSystem!!.memoryAvailableBytes.single().at).isEqualTo(since)
    }

    @Test fun `'요청 수 반올림' 60을 곱한 값 3점8은 4`(): Unit = InfraFixture().use { f ->
        f.prometheus.bodies[PrometheusStub.SERIES[0]] = PrometheusStub.matrix(mapOf<String, String>() to listOf(since.plusSeconds(300) to "3.8"))
        f.prometheus.bodies[PrometheusStub.SERIES[1]] = PrometheusStub.matrix(mapOf<String, String>() to listOf(since.plusSeconds(300) to "0.4"))
        val requests = f.service.metrics(InfraMetricsQuery()).requests!!
        assertThat(requests.requests.single().value).isEqualTo(4.0); assertThat(requests.server5xx.single().value).isEqualTo(0.0)
        assertThat(f.prometheus.requests.single { it.param("query")!!.contains("http_requests") }.param("query")).contains("* 60")
    }

    @Test fun `'요청 없는 구간의 p95' NaN 점은 뺀다`(): Unit = InfraFixture().use { f ->
        f.prometheus.bodies[PrometheusStub.SERIES[2]] = PrometheusStub.matrix(mapOf<String, String>() to
            listOf(since.plusSeconds(300) to "182", since.plusSeconds(600) to "NaN"))
        assertThat(f.service.metrics(InfraMetricsQuery()).requests!!.responseTimeP95Ms.map { it.value }).containsExactly(182.0)
    }

    @Test fun `'계열 없음' OpenSearch 계열이 비면 묶음은 있고 값은 null과 빈 목록`(): Unit = InfraFixture().use { f ->
        for (series in PrometheusStub.SERIES.filter { it.startsWith("manyak_opensearch") }) {
            f.prometheus.bodies[series] = if (series.contains("total") || series.contains("status")) PrometheusStub.vector() else PrometheusStub.matrix()
        }
        val result = f.service.metrics(InfraMetricsQuery())
        val search = result.opensearch!!
        assertThat(search.status).isNull(); assertThat(search.diskTotalBytes).isNull()
        assertThat(search.diskAvailableBytes).isEmpty(); assertThat(search.jvmMemoryPressure).isEmpty()
        assertThat(result.unavailable).isEmpty()
    }

    @Test fun `'디스크 순서' data 다음 루트로 와도 루트부터`(): Unit = InfraFixture().use { f ->
        f.prometheus.bodies[PrometheusStub.SERIES[5]] = PrometheusStub.matrix(
            mapOf("mountpoint" to "/data") to listOf(since.plusSeconds(300) to "10610000000"),
            mapOf("mountpoint" to "/") to listOf(since.plusSeconds(300) to "15400000000"))
        f.prometheus.bodies[PrometheusStub.SERIES[6]] = PrometheusStub.vector(mapOf("mountpoint" to "/data") to "10726000000", mapOf("mountpoint" to "/") to "21462000000")
        val disks = f.service.metrics(InfraMetricsQuery()).hostSystem!!.disks
        assertThat(disks.map { it.mount }).containsExactly("/", "/data")
        assertThat(disks.map { it.totalBytes }).containsExactly(21462000000L, 10726000000L)
        assertThat(disks.map { it.availableBytes.single().value }).containsExactly(15400000000.0, 10610000000.0)
    }

    @Test fun `'클러스터 상태' 0 1 2는 green yellow red`(): Unit = InfraFixture().use { f ->
        for ((value, expected) in listOf("0" to "green", "1" to "yellow", "2" to "red")) {
            f.prometheus.bodies[PrometheusStub.SERIES[10]] = PrometheusStub.vector(mapOf<String, String>() to value)
            assertThat(f.service.metrics(InfraMetricsQuery()).opensearch!!.status).isEqualTo(expected)
            f.clock.at = f.clock.at.plusSeconds(61)
        }
    }

    @Test fun `'Redis 값' 대역의 다섯 값을 그대로 담는다`(): Unit = InfraFixture().use { f ->
        assertThat(f.service.metrics(InfraMetricsQuery()).redis).isEqualTo(InfraRedisMetrics(2474639, 268435456, 6, 7450, true))
    }

    @Test fun `'묶음 설정 없음' 오토스케일링 그룹만 비면 host만 null이고 앱 호스트 질의가 없다`(): Unit =
        InfraFixture({ InfraMetricsProperties(hostAsg = "", database = "private-db", prometheusUrl = it) }).use { f ->
            val result = f.service.metrics(InfraMetricsQuery())
            assertThat(result.host).isNull(); assertThat(result.unavailable).isEmpty()
            assertThat(f.requests.single().metricDataQueries()).hasSize(5).noneMatch { it.metricStat().metric().namespace() == "AWS/EC2" }
            assertThat(listOf(result.rds, result.hostSystem, result.requests, result.redis, result.opensearch)).doesNotContainNull()
        }

    @Test fun `'Prometheus 설정 없음' 조회 주소가 비면 세 묶음이 null이고 부르지 않는다`(): Unit =
        InfraFixture({ infraFullProperties("") }).use { f ->
            val result = f.service.metrics(InfraMetricsQuery())
            assertThat(listOf(result.hostSystem, result.requests, result.opensearch)).containsOnlyNulls()
            assertThat(result.unavailable).isEmpty(); assertThat(f.prometheus.requests).isEmpty()
            assertThat(listOf(result.host, result.rds, result.redis)).doesNotContainNull()
        }

    @Test fun `'설정 전부 없음' CloudWatch와 Prometheus 설정이 비면 redis만 응답한다`(): Unit =
        InfraFixture({ InfraMetricsProperties() }).use { f ->
            val result = f.service.metrics(InfraMetricsQuery())
            assertThat(result.redis).isNotNull(); assertThat(result.unavailable).isEmpty()
            assertThat(listOf(result.host, result.hostSystem, result.requests, result.rds, result.opensearch)).containsOnlyNulls()
            verifyNoInteractions(f.cw); assertThat(f.prometheus.requests).isEmpty()
        }

    @Test fun `'Prometheus 실패' 한 질의의 500이면 세 묶음만 비우고 prometheus_error`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        f.prometheus.failure = { if (it.param("query")!!.contains("memory_total")) MockResponse().setResponseCode(500) else null }
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(result.unavailable).containsExactly("hostSystem", "requests", "opensearch")
        assertThat(listOf(result.hostSystem, result.requests, result.opensearch)).containsOnlyNulls()
        assertThat(listOf(result.host, result.rds, result.redis)).doesNotContainNull()
        assertThat(logs.list.map { it.formattedMessage }).containsExactly("admin_infra_metrics_fetch_failed reason=prometheus_error service=prometheus error_code=500")
    } }

    @Test fun `'Prometheus 실패' 응답의 status가 success가 아니면 errorType을 기록한다`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        f.prometheus.failure = { MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"error","errorType":"bad_data","error":"PRIVATE"}""") }
        assertThat(f.service.metrics(InfraMetricsQuery()).unavailable).containsExactly("hostSystem", "requests", "opensearch")
        assertThat(logs.list.single().formattedMessage).contains("reason=prometheus_error", "error_code=bad_data").doesNotContain("PRIVATE")
    } }

    @Test fun `'Prometheus 인증 거부'와 '인증 정보' 403은 access_denied이고 요청에 두 인증이 있으며 로그에 비밀이 없다`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        f.prometheus.failure = { MockResponse().setResponseCode(403) }
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(listOf(result.hostSystem, result.requests, result.opensearch)).containsOnlyNulls()
        assertThat(logs.list.single().formattedMessage).contains("reason=access_denied", "service=prometheus", "error_code=403")
        val basic = "Basic " + Base64.getEncoder().encodeToString("private-reader:private-password".toByteArray())
        assertThat(f.prometheus.requests).isNotEmpty.allMatch { it.getHeader("CF-Access-Client-Id") == "private-client-id" &&
            it.getHeader("CF-Access-Client-Secret") == "private-client-secret" && it.getHeader("Authorization") == basic }
        assertThat(logs.list.joinToString { it.formattedMessage }).doesNotContain("private-client-id", "private-client-secret", "private-password", "private-reader")
    } }

    @Test fun `'Prometheus 시간 초과' 3초 넘게 무응답이면 timeout이고 나머지 묶음은 정상`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        f.prometheus.failure = { MockResponse().setHeader("Content-Type", "application/json").setBody(PrometheusStub.vector()).setHeadersDelay(4, TimeUnit.SECONDS) }
        val start = System.nanoTime()
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(2900L, 4000L)
        assertThat(listOf(result.hostSystem, result.requests, result.opensearch)).containsOnlyNulls()
        assertThat(listOf(result.host, result.rds, result.redis)).doesNotContainNull()
        assertThat(logs.list.single().formattedMessage).contains("reason=timeout", "service=prometheus")
    } }

    @Test fun `'CloudWatch 권한 거부'와 '호출 제한' host와 rds만 비우고 원문 없이 기록한다`(): Unit = infraLogs { logs ->
        for ((code, reason) in listOf("AccessDeniedException" to "access_denied", "ThrottlingException" to "throttled")) InfraFixture().use { f ->
            f.failCloudWatch(code)
            val result = f.service.metrics(InfraMetricsQuery())
            assertThat(result.unavailable).containsExactly("host", "rds"); assertThat(result.host).isNull(); assertThat(result.rds).isNull()
            assertThat(listOf(result.hostSystem, result.requests, result.redis, result.opensearch)).doesNotContainNull()
            assertThat(logs.list.last().formattedMessage).isEqualTo("admin_infra_metrics_fetch_failed reason=$reason service=cloudwatch error_code=$code")
        }
        assertThat(logs.list.joinToString { it.formattedMessage }).doesNotContain("PRIVATE")
    }

    @Test fun `'Redis 실패' 예외면 redis만 비우고 redis_error`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        `when`(f.redis.fetch()).thenThrow(IllegalStateException("PRIVATE REDIS MESSAGE"))
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(result.unavailable).containsExactly("redis"); assertThat(result.redis).isNull()
        assertThat(logs.list.single().formattedMessage).isEqualTo("admin_infra_metrics_fetch_failed reason=redis_error service=redis error_code=null")
    } }

    @Test fun `'모두 실패' 캐시가 없으면 503이고 출처마다 로그 한 줄`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        f.failAll()
        assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
        assertThat(logs.list.map { Regex("service=(\\w+)").find(it.formattedMessage)!!.groupValues[1] }).containsExactlyInAnyOrder("cloudwatch", "prometheus", "redis")
    } }

    @Test fun `'불완전 결과' PartialData로 끝나면 host와 rds를 비우고 0으로 채우지 않는다`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { q ->
                MetricDataResult.builder().id(q.id()).statusCode(if (q.id() == "rds_cpu") StatusCode.PARTIAL_DATA else StatusCode.COMPLETE).build()
            }).build()
        }
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(result.host).isNull(); assertThat(result.rds).isNull(); assertThat(result.unavailable).containsExactly("host", "rds")
        assertThat(logs.list.single().formattedMessage).contains("reason=incomplete", "service=cloudwatch", "error_code=PartialData")
    } }

    @Test fun `'캐시'와 '캐시 만료'와 '범위별 캐시' 60초 안은 한 번 61초 뒤와 다른 범위는 다시 부른다`(): Unit = InfraFixture().use { f ->
        val first = f.service.metrics(InfraMetricsQuery())
        f.clock.at = f.clock.at.plusSeconds(59)
        assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        verify(f.cw, times(1)).getMetricData(any(GetMetricDataRequest::class.java)); verify(f.redis, times(1)).fetch()
        assertThat(f.prometheus.requests).hasSize(11)
        f.service.metrics(InfraMetricsQuery().apply { from = "2026-11-19"; to = "2026-11-19" })
        verify(f.cw, times(2)).getMetricData(any(GetMetricDataRequest::class.java))
        f.clock.at = f.clock.at.plusSeconds(2)
        assertThat(f.service.metrics(InfraMetricsQuery())).isNotSameAs(first)
        verify(f.cw, times(3)).getMetricData(any(GetMetricDataRequest::class.java))
    }

    @Test fun `'일부 실패의 캐시' Prometheus만 실패한 응답도 60초 보관하고 61초 뒤 세 출처를 다시 부른다`(): Unit = InfraFixture().use { f ->
        f.prometheus.failure = { MockResponse().setResponseCode(500) }
        val first = f.service.metrics(InfraMetricsQuery())
        val calls = f.prometheus.requests.size
        f.clock.at = f.clock.at.plusSeconds(59)
        assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        assertThat(f.prometheus.requests).hasSize(calls); verify(f.redis, times(1)).fetch()
        f.prometheus.failure = null; f.clock.at = f.clock.at.plusSeconds(2)
        assertThat(f.service.metrics(InfraMetricsQuery()).unavailable).isEmpty()
        verify(f.cw, times(2)).getMetricData(any(GetMetricDataRequest::class.java)); verify(f.redis, times(2)).fetch()
        assertThat(f.prometheus.requests.size).isGreaterThan(calls)
    }

    @Test fun `'실패와 캐시'와 '쿨다운' 모두 실패하면 만료된 캐시나 503이고 30초 안에는 다시 부르지 않는다`(): Unit = InfraFixture().use { f ->
        val first = f.service.metrics(InfraMetricsQuery())
        f.clock.at = f.clock.at.plusSeconds(61); f.failAll()
        assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        val other = InfraMetricsQuery().apply { from = "2026-11-18"; to = "2026-11-18" }
        assertThatThrownBy { f.service.metrics(other) }.isInstanceOf(InfraMetricsUnavailable::class.java)
        val calls = mockingDetails(f.redis).invocations.size
        f.clock.at = f.clock.at.plusSeconds(30)
        assertThatThrownBy { f.service.metrics(other) }.isInstanceOf(InfraMetricsUnavailable::class.java)
        assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
        assertThat(mockingDetails(f.redis).invocations).hasSize(calls)
    }

    @Test fun `갱신은 키마다 하나뿐이며 동시 요청은 기다리지 않고 사용 불가 또는 만료 값을 받는다`(): Unit = InfraFixture().use { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        `when`(f.redis.fetch()).thenAnswer { entered.countDown(); release.await(3, TimeUnit.SECONDS); InfraRedisMetrics(1, 0, 1, 1, null) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<InfraMetricsResponse> { f.service.metrics(InfraMetricsQuery()) }
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { f.service.metrics(InfraMetricsQuery()) }.isInstanceOf(InfraMetricsUnavailable::class.java)
            release.countDown(); val first = running.get(3, TimeUnit.SECONDS)
            f.clock.at = f.clock.at.plusSeconds(61)
            val entered2 = CountDownLatch(1); val release2 = CountDownLatch(1)
            `when`(f.redis.fetch()).thenAnswer { entered2.countDown(); release2.await(3, TimeUnit.SECONDS); InfraRedisMetrics(1, 0, 1, 1, null) }
            val update = pool.submit<InfraMetricsResponse> { f.service.metrics(InfraMetricsQuery()) }
            assertThat(entered2.await(2, TimeUnit.SECONDS)).isTrue(); assertThat(f.service.metrics(InfraMetricsQuery())).isSameAs(first)
            release2.countDown(); update.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `최근 100개 범위만 보관하고 접근한 키를 최근으로 갱신한다`(): Unit = InfraFixture({ InfraMetricsProperties(database = "private-db") }).use { f ->
        fun q(offset: Long) = InfraMetricsQuery().apply { from = LocalDate.parse("2026-11-20").minusDays(offset).toString(); to = from }
        (0L..99L).forEach { f.service.metrics(q(it)) }
        f.service.metrics(q(0)); f.service.metrics(q(100)); f.service.metrics(q(1))
        verify(f.cw, times(102)).getMetricData(any(GetMetricDataRequest::class.java))
    }

    @Test fun `세 출처는 합쳐 5초 한도를 공유하고 늦은 출처만 timeout으로 비운다`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        `when`(f.cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { Thread.sleep(10000); GetMetricDataResponse.builder().build() }
        val start = System.nanoTime()
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(4500L, 6500L)
        assertThat(result.unavailable).containsExactly("host", "rds"); assertThat(result.redis).isNotNull()
        assertThat(logs.list.single().formattedMessage).contains("reason=timeout", "service=cloudwatch")
    } }

    @Test fun `Redis는 1초 한도를 넘기면 timeout으로 비운다`(): Unit = infraLogs { logs -> InfraFixture().use { f ->
        `when`(f.redis.fetch()).thenAnswer { Thread.sleep(3000); InfraRedisMetrics(1, 0, 1, 1, null) }
        val start = System.nanoTime()
        val result = f.service.metrics(InfraMetricsQuery())
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(2500L)
        assertThat(result.unavailable).containsExactly("redis")
        assertThat(logs.list.single().formattedMessage).contains("reason=timeout", "service=redis")
    } }
}
