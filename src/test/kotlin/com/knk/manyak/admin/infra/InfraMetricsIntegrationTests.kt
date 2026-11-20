package com.knk.manyak.admin.infra

import com.knk.manyak.admin.analytics.MutableAnalyticsClock
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.AdminMetricsIntegrationTest
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import tools.jackson.databind.ObjectMapper
import java.time.*

class InfraMetricsIntegrationTests : AdminMetricsIntegrationTest() {
    override val clockAt: Instant = Instant.parse("2026-11-20T00:00:00Z")
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var clock: MutableAnalyticsClock
    @Autowired private lateinit var repository: InfraDatabaseRepository
    @Autowired private lateinit var cw: CloudWatchClient
    @Autowired private lateinit var prometheus: PrometheusInfraClient
    @Autowired private lateinit var redis: RedisInfraClient
    private val mapper = ObjectMapper()
    private val start = Instant.parse("2026-11-19T15:00:00Z")
    private fun point(value: Double) = listOf(InfraMetricPoint(start, value))

    private fun stubSources() {
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { call ->
            val request = call.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { q ->
                MetricDataResult.builder().id(q.id()).statusCode(StatusCode.COMPLETE).timestamps(request.startTime()).values(1.0).build()
            }).build()
        }
        `when`(prometheus.fetch(any(InfraRange::class.java) ?: InfraRange(LocalDate.MIN, LocalDate.MIN, Instant.EPOCH, Instant.EPOCH, 300),
            any(Instant::class.java) ?: Instant.EPOCH, anyLong())).thenReturn(InfraPrometheusValues(
            InfraHostSystemMetrics(4015000000, point(1876000000.0), listOf(InfraDisk("/", 21462000000, point(15400000000.0)))),
            InfraRequestMetrics(point(312.0), point(1.0), point(182.0)),
            InfraSearchMetrics("green", 137170518016, point(114949550080.0), point(9.0))))
        `when`(redis.fetch()).thenReturn(InfraRedisMetrics(2474639, 268435456, 6, 7450, true))
    }

    @Test fun `인프라 두 API는 권한 날짜 개수 '응답의 이름' 캐시 실패 '격리' 계약을 따른다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자")); val ordinary = users.save(User(nickname = "일반"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        val token = jwt.issueAdminToken(admin.publicId)
        fun get(path: String) = client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer $token").exchange()
        for (path in listOf("/infra/metrics", "/infra/db-tables")) {
            client.get().uri("/api/v1/admin$path").exchange().expectStatus().isUnauthorized
            client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer ${jwt.issueAccessToken(admin.publicId)}").exchange().expectStatus().isUnauthorized
            client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer ${jwt.issueAdminToken(ordinary.publicId)}").exchange().expectStatus().isForbidden
        }
        for (query in listOf("from=2026-11-20", "from=bad&to=bad", "from=2026-11-20&to=2026-11-21", "from=2026-10-20&to=2026-11-20", "from=2026-11-20&to=2026-11-19")) {
            get("/infra/metrics?$query").expectStatus().isBadRequest
        }
        for (limit in listOf("0", "101", "abc", "1.5", "")) get("/infra/db-tables?limit=$limit").expectStatus().isBadRequest
        verifyNoInteractions(cw, prometheus, redis, repository)

        stubSources()
        val raw = get("/infra/metrics").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        val body = mapper.readTree(raw)
        assertThat(body["from"].asText()).isEqualTo("2026-11-20"); assertThat(body["periodSeconds"].asInt()).isEqualTo(300)
        assertThat(body["unavailable"].size()).isZero()
        assertThat(body["host"]["cpu"][0]["at"].asText()).isEqualTo("2026-11-19T15:00:00Z")
        assertThat(body["host"].propertyNames()).containsExactlyInAnyOrder("cpu", "cpuMax", "cpuCreditBalance", "statusCheckFailed")
        assertThat(body["hostSystem"]["disks"][0]["mount"].asText()).isEqualTo("/")
        assertThat(body["requests"]["requests"][0]["value"].asDouble()).isEqualTo(312.0)
        assertThat(body["rds"]["readLatencyMs"][0]["value"].asDouble()).isEqualTo(1000.0)
        assertThat(body["redis"]["aofLastWriteOk"].asBoolean()).isTrue()
        assertThat(body["opensearch"]["status"].asText()).isEqualTo("green")
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("asOf", "from", "to", "periodSeconds", "unavailable",
            "host", "hostSystem", "requests", "rds", "redis", "opensearch")
        assertThat(raw).doesNotContain("private-asg", "private-rds", "private-prometheus", "private-reader", "private-password", "private-client")
        get("/infra/metrics").expectStatus().isOk
        verify(cw, times(1)).getMetricData(any(GetMetricDataRequest::class.java)); verify(redis, times(1)).fetch()

        val tables = listOf(InfraTableSize("public", "a", 300, 200, 50, 10), InfraTableSize("public", "c", 100, 50, 20, 1), InfraTableSize("public", "b", 200, 120, 40, 5))
        `when`(repository.snapshot(anyInt())).thenReturn(InfraDatabaseSnapshot(1000, 3, tables))
        val db = mapper.readTree(get("/infra/db-tables?limit=2").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(db["tableCount"].asInt()).isEqualTo(3); assertThat(db["databaseBytes"].asInt()).isEqualTo(1000)
        assertThat(db["tables"].toList().map { it["name"].asText() }).containsExactly("a", "b")
        get("/infra/db-tables?limit=2").expectStatus().isOk; verify(repository, times(1)).snapshot(2)

        // Redis만 실패하면 200이고 그 묶음만 null이다. 키는 그대로 남는다.
        clock.at = clock.at.plusSeconds(61)
        `when`(redis.fetch()).thenThrow(IllegalStateException("PRIVATE"))
        val partial = mapper.readTree(get("/infra/metrics").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(partial["unavailable"].toList().map { it.asText() }).containsExactly("redis")
        assertThat(partial["redis"].isNull).isTrue(); assertThat(partial["host"].isNull).isFalse()

        // 세 출처가 모두 실패하면 만료된 캐시로 답하고, 캐시가 없는 범위는 503이다. 다른 대시보드와 DB 테이블 크기는 영향이 없다.
        clock.at = clock.at.plusSeconds(61)
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenThrow(CloudWatchException.builder().statusCode(500).message("PRIVATE AWS MESSAGE").build())
        `when`(prometheus.fetch(any(InfraRange::class.java) ?: InfraRange(LocalDate.MIN, LocalDate.MIN, Instant.EPOCH, Instant.EPOCH, 300),
            any(Instant::class.java) ?: Instant.EPOCH, anyLong())).thenThrow(InfraFetchFailure("prometheus_error", "prometheus", "500"))
        get("/infra/metrics").expectStatus().isOk.expectBody().jsonPath("$.asOf").isEqualTo(partial["asOf"].asText())
        get("/infra/metrics?from=2026-11-19&to=2026-11-19").expectStatus().isEqualTo(503).expectBody()
            .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE").jsonPath("$.message").isEqualTo("인프라 지표를 가져올 수 없습니다.")
        get("/dashboard/summary").expectStatus().isOk; get("/dashboard/charts?days=7").expectStatus().isOk; get("/infra/db-tables").expectStatus().isOk

        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", admin.id)
        for (path in listOf("/infra/metrics", "/infra/db-tables")) get(path).expectStatus().isForbidden
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", admin.id)
        jdbc.update("DELETE FROM admin_accounts WHERE user_id=?", admin.id)
        for (path in listOf("/infra/metrics", "/infra/db-tables")) get(path).expectStatus().isForbidden
    }
}
