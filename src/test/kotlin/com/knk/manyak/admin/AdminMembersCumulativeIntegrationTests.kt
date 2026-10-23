package com.knk.manyak.admin

import com.knk.manyak.admin.repository.AdminDashboardRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@AutoConfigureRestTestClient
@Import(com.knk.manyak.admin.analytics.AdminMetricsTestClock::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids=900001,900002,900003"])
class AdminMembersCumulativeIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var dashboard: AdminDashboardRepository
    private val mapper = ObjectMapper()
    private val first = Instant.parse("2026-10-21T15:00:00Z") // 10/22 KST 자정
    private val today = first.plusSeconds(86400)
    private val now = Instant.parse("2026-10-23T03:00:00Z")
    private lateinit var token: String
    private fun member(id: Long, at: Instant, status: String = "ACTIVE"): UUID {
        val publicId = UUID.randomUUID()
        jdbc.update("""INSERT INTO users(id,public_id,nickname,status,created_at,updated_at,migration_attempts,service_push_enabled)
            VALUES (?,?,?,?,?,?,0,true)""", id, publicId, "회원$id", status, Timestamp.from(at), Timestamp.from(at))
        return publicId
    }
    @BeforeEach fun setup() {
        cleaner.cleanAll()
        val adminId = member(900001, Instant.parse("2020-01-01T00:00:00Z"))
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,?)", 900001, Timestamp.from(now))
        token = jwt.issueAdminToken(adminId)
    }
    private fun get(path: String): JsonNode = mapper.readTree(client.get().uri("/api/v1/admin$path")
        .header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `요약은 팀 제외 뒤 탈퇴 포함 누적과 현재 정지 포함 회원 및 탈퇴 수를 나눈다`() {
        member(101, today.minusMillis(1)); member(102, today)
        member(103, first, "SUSPENDED"); member(104, today.plusMillis(1), "DELETED")
        member(900002, today); member(900003, today, "DELETED")
        val m = get("/dashboard/summary")["members"]
        assertThat(m["total"].asLong()).isEqualTo(4)
        assertThat(m["active"].asLong()).isEqualTo(3)
        assertThat(m["withdrawn"].asLong()).isEqualTo(1)
        assertThat(m["total"].asLong()).isEqualTo(m["active"].asLong() + m["withdrawn"].asLong())
        assertThat(m["joinedToday"].asLong()).isEqualTo(2)
        // 제외 목록을 비운 기존 경로도 모든 상태를 집계한다.
        assertThat(dashboard.summary(now, today, emptyList()).members.total).isEqualTo(7)
    }

    @Test fun `가입 누계는 KST 시작 전 회원과 탈퇴를 포함하고 같은 날 값은 범위를 바꿔도 같다`() {
        member(101, Instant.parse("2020-01-01T00:00:00Z"), "DELETED")
        (102L..105L).forEach { member(it, first.minusMillis(1)) }
        member(106, first); member(107, first.plusSeconds(3600))
        member(108, today, "DELETED")
        member(900002, first.minusMillis(1)); member(900003, first, "DELETED")
        val daily = get("/users/signups?from=2026-10-22&to=2026-10-23")["daily"].toList()
        assertThat(daily.map { it["signups"]["total"].asLong() }).containsExactly(2, 1)
        assertThat(daily.map { it["cumulativeSignups"].asLong() }).containsExactly(7, 8)
        assertThat(daily.last()["cumulativeSignups"].asLong()).isEqualTo(get("/dashboard/summary")["members"]["total"].asLong())
        val narrow = get("/users/signups?from=2026-10-23&to=2026-10-23")["daily"]
        assertThat(narrow[0]["cumulativeSignups"].asLong()).isEqualTo(8)
        val wide = get("/users/signups?from=2026-10-20&to=2026-10-23")["daily"].toList()
        assertThat(wide.takeLast(2)).isEqualTo(daily)
        assertThat(wide.first()["signups"]["total"].asLong()).isZero()
        assertThat(wide.first()["cumulativeSignups"].asLong()).isEqualTo(1)
    }

    @Test fun `팀 계정만 있으면 회원 요약과 가입 누계는 모두 0이다`() {
        val m = get("/dashboard/summary")["members"]
        for (field in listOf("total", "active", "withdrawn", "joinedToday")) assertThat(m[field].asLong()).isZero()
        val daily = get("/users/signups?from=2026-10-22&to=2026-10-23")["daily"]
        assertThat(daily).hasSize(2)
        daily.forEach { assertThat(it["cumulativeSignups"].asLong()).isZero() }
    }
}
