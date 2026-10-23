package com.knk.manyak.admin.infra

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.admin.analytics.MutableAnalyticsClock
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient
import software.amazon.awssdk.services.cloudwatch.model.*
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.*
import tools.jackson.databind.ObjectMapper
import java.time.*

@AutoConfigureRestTestClient
@org.springframework.context.annotation.Import(InfraApiClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "manyak.admin.infra.cluster=private-cluster", "manyak.admin.infra.services=server=private-server,ai=private-ai,notification=private-notification",
    "manyak.admin.infra.load-balancer=private-alb", "manyak.admin.infra.database=private-rds", "manyak.admin.infra.cache-clusters=redis-001,redis-002",
    "manyak.admin.infra.domain=private-domain", "manyak.admin.infra.account-id=123456789012"])
class InfraMetricsIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var clock: MutableAnalyticsClock
    @MockitoBean private lateinit var repository: InfraDatabaseRepository
    @MockitoBean private lateinit var cw: CloudWatchClient
    @MockitoBean private lateinit var ecs: EcsClient
    private val mapper = ObjectMapper()
    @Test fun `인프라 두 API는 권한 날짜 개수 노출 캐시 실패 격리 계약을 따른다`() {
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
        for (query in listOf("from=2026-10-23", "from=bad&to=bad", "from=2026-10-23&to=2026-10-24", "from=2026-09-22&to=2026-10-23", "from=2026-10-23&to=2026-10-22")) get("/infra/metrics?$query").expectStatus().isBadRequest
        for (limit in listOf("0", "101", "abc", "1.5", "")) get("/infra/db-tables?limit=$limit").expectStatus().isBadRequest
        verifyNoInteractions(cw, ecs, repository)
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { call ->
            val request = call.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { q -> MetricDataResult.builder().id(q.id()).statusCode(StatusCode.COMPLETE).timestamps(request.startTime()).values(1.0).build() }).build()
        }
        `when`(ecs.describeServices(any(DescribeServicesRequest::class.java))).thenReturn(DescribeServicesResponse.builder().services(listOf("private-server", "private-ai", "private-notification").map {
            software.amazon.awssdk.services.ecs.model.Service.builder().serviceName(it).runningCount(2).desiredCount(2).pendingCount(0).build() }).build())
        val raw = get("/infra/metrics").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!
        val body = mapper.readTree(raw)
        assertThat(body["from"].asText()).isEqualTo("2026-10-23"); assertThat(body["periodSeconds"].asInt()).isEqualTo(300)
        assertThat(body["ecs"]["services"].size()).isEqualTo(3)
        assertThat(body["ecs"]["services"][0]["runningTasks"].asInt()).isEqualTo(2)
        assertThat(body["alb"]["requests"].size()).isEqualTo(108)
        assertThat(raw).doesNotContain("123456789012", "private-cluster", "private-server", "private-alb", "private-rds", "private-domain")
        get("/infra/metrics").expectStatus().isOk
        verify(cw, times(1)).getMetricData(any(GetMetricDataRequest::class.java)); verify(ecs, times(1)).describeServices(any(DescribeServicesRequest::class.java))
        val tables = listOf(InfraTableSize("public", "a", 300, 200, 50, 10), InfraTableSize("public", "c", 100, 50, 20, 1), InfraTableSize("public", "b", 200, 120, 40, 5))
        `when`(repository.snapshot(anyInt())).thenReturn(InfraDatabaseSnapshot(1000, 3, tables))
        val db = mapper.readTree(get("/infra/db-tables?limit=2").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(db["tableCount"].asInt()).isEqualTo(3); assertThat(db["databaseBytes"].asInt()).isEqualTo(1000)
        assertThat(db["tables"].toList().map { it["name"].asText() }).containsExactly("a", "b")
        get("/infra/db-tables?limit=2").expectStatus().isOk; verify(repository, times(1)).snapshot(2)
        clock.at = clock.at.plusSeconds(61)
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { call ->
            val request = call.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { q ->
                MetricDataResult.builder().id(q.id()).statusCode(if (q.id() == "alb_requests") StatusCode.PARTIAL_DATA else StatusCode.COMPLETE).build()
            }).build()
        }
        get("/infra/metrics?from=2026-10-20&to=2026-10-20").expectStatus().isEqualTo(503)
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenAnswer { call ->
            val request = call.getArgument<GetMetricDataRequest>(0)
            GetMetricDataResponse.builder().metricDataResults(request.metricDataQueries().map { q ->
                MetricDataResult.builder().id(q.id()).statusCode(StatusCode.FORBIDDEN).build()
            }).build()
        }
        get("/infra/metrics").expectStatus().isOk.expectBody().jsonPath("$.asOf").isEqualTo(body["asOf"].asText())
        clock.at = clock.at.plusSeconds(61)
        `when`(cw.getMetricData(any(GetMetricDataRequest::class.java))).thenThrow(CloudWatchException.builder().statusCode(500).message("PRIVATE AWS MESSAGE").build())
        get("/infra/metrics").expectStatus().isOk.expectBody().jsonPath("$.asOf").isEqualTo(body["asOf"].asText())
        get("/infra/metrics?from=2026-10-21&to=2026-10-21").expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE").jsonPath("$.message").isEqualTo("인프라 지표를 가져올 수 없습니다.")
        get("/dashboard/summary").expectStatus().isOk; get("/dashboard/charts?days=7").expectStatus().isOk; get("/infra/db-tables").expectStatus().isOk
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", admin.id)
        for (path in listOf("/infra/metrics", "/infra/db-tables")) get(path).expectStatus().isForbidden
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", admin.id)
        jdbc.update("DELETE FROM admin_accounts WHERE user_id=?", admin.id)
        for (path in listOf("/infra/metrics", "/infra/db-tables")) get(path).expectStatus().isForbidden
    }
}
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
class InfraApiClock {
    @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
    fun infraClock() = MutableAnalyticsClock(Instant.parse("2026-10-23T00:00:00Z"))
}
