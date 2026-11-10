package com.knk.manyak.admin

import com.knk.manyak.support.SharedPostgres
import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.repository.AdminModerationMetricsRepository
import com.knk.manyak.admin.service.AdminModerationMetricsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tools.jackson.databind.ObjectMapper
import java.time.*
import java.util.UUID

class AdminModerationMetricsPostgresTests {
    @Test fun `실제 PostgreSQL에서 JSONB 제출본별 사유와 현재 보류 및 재제출 덮어쓰기를 집계한다`() {
        val ds = SharedPostgres.database(migrated = true)
        val jdbc = JdbcTemplate(ds)
        val mapper = ObjectMapper()
        val now = Instant.parse("2026-10-20T15:30:00Z")
        val before = Instant.parse("2026-10-20T14:59:59.999999Z")
        val midnight = Instant.parse("2026-10-20T15:00:00Z")
        val old = Instant.parse("2020-01-01T00:00:00Z")
        val owner = jdbc.queryForObject("INSERT INTO users(public_id,nickname) VALUES (?,'검수 팀 PG') RETURNING id", Long::class.java, UUID.randomUUID())!!
        val story = jdbc.queryForObject("INSERT INTO stories(public_id,user_id,title) VALUES (?,?,'검수 PG') RETURNING id",
            Long::class.java, UUID.randomUUID(), owner)!!
        fun issue(rule: String = "EXPLICIT_SEXUAL_CONTENT", type: String = "TEXT", path: String = "title") =
            mapOf("path" to path, "rule" to rule, "type" to type, "reason" to "fixture")
        fun submission(status: String, created: Instant = old, decided: Instant? = if (status == "PENDING") null else now,
                       held: Boolean = false, issues: List<Map<String, String>> = emptyList()) = jdbc.queryForObject("""
            INSERT INTO story_submissions(public_id,user_id,story_id,kind,payload,input_form,status,issues,error_code,created_at,decided_at,held_at,hold_reason)
            VALUES (?,?,?,'CREATE','{}','{}',?,CAST(? AS jsonb),?,?,?,?,?) RETURNING id""",
            Long::class.java, UUID.randomUUID(), owner, if (status == "APPROVED") story else null, status,
            mapper.writeValueAsString(issues), if (status == "FAILED") "AI_FAILED" else null,
            created.atOffset(ZoneOffset.UTC), decided?.atOffset(ZoneOffset.UTC), if (held) now.atOffset(ZoneOffset.UTC) else null,
            if (held) "RETRY_EXHAUSTED" else null)!!
        repeat(6) { submission("APPROVED") }
        submission("REJECTED", before, issues = listOf(issue(), issue(path = "description"), issue(type = "IMAGE"), issue("DRUGS")))
        submission("REJECTED", midnight, issues = listOf(issue(), issue("HATE_VIOLENCE_INCITEMENT")))
        submission("FAILED", now)
        submission("REJECTED", decided = before)
        submission("REJECTED", now.plusNanos(1000), now.plusNanos(1000), issues = listOf(issue("DRUGS")))
        submission("PENDING", held = true)
        repeat(3) { submission("PENDING") }
        val resubmitted = submission("REJECTED", before, before, issues = listOf(issue("EXTREME_GORE")))
        val service = AdminModerationMetricsService(AdminModerationMetricsRepository(NamedParameterJdbcTemplate(jdbc), mapper),
            Clock.fixed(now, ZoneOffset.UTC))
        val historical = AdminChartsRequest().apply { from = "2026-10-20"; to = "2026-10-20" }
        assertThat(service.metrics(historical).rejected).isEqualTo(2)
        jdbc.update("UPDATE story_submissions SET status='PENDING',decided_at=NULL,issues='[]',attempt=2,updated_at=? WHERE id=?",
            now.atOffset(ZoneOffset.UTC), resubmitted)
        val result = service.metrics(AdminChartsRequest().apply { from = "2026-10-21"; to = "2026-10-21" })
        assertThat(result.decided).isEqualTo(8)
        assertThat(result.approved).isEqualTo(6)
        assertThat(result.rejected).isEqualTo(2)
        assertThat(result.failed).isEqualTo(1)
        assertThat(result.rejectionRate).isEqualTo(0.25)
        assertThat(result.held).isEqualTo(1)
        assertThat(result.pending).isEqualTo(4)
        assertThat(result.daily.single().submitted).isEqualTo(2)
        assertThat(result.rejectionRules.map { it.rule }).containsExactly("EXPLICIT_SEXUAL_CONTENT", "DRUGS", "EXPLICIT_SEXUAL_CONTENT", "HATE_VIOLENCE_INCITEMENT")
        assertThat(result.rejectionRules.map { it.type }).containsExactly("TEXT", "TEXT", "IMAGE", "TEXT")
        assertThat(result.rejectionRules.map { it.submissions }).containsExactly(2, 1, 1, 1)
        val after = service.metrics(historical)
        assertThat(after.rejected).isEqualTo(1)
        assertThat(after.daily.single().submitted).isEqualTo(2)
        assertThat(after.rejectionRules).isEmpty()
        assertThat(after.held).isEqualTo(result.held)
        assertThat(after.pending).isEqualTo(result.pending)
        val empty = service.metrics(AdminChartsRequest().apply { from = "2019-01-01"; to = "2019-01-03" })
        assertThat(empty.rejectionRate).isNull()
        assertThat(empty.daily).hasSize(3)
        assertThat(empty.daily.sumOf { it.submitted + it.approved + it.rejected + it.failed }).isZero()
    }
}
