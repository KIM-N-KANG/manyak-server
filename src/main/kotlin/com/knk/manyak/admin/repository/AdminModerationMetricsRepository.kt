package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.AdminModerationMetricsDay
import com.knk.manyak.admin.dto.AdminModerationRejectionRule
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AdminModerationBacklog(val held: Long, val pending: Long)

/** 검수 지표는 팀 계정을 포함하며 제출 원문·감사 이력을 읽지 않는다. */
@Repository
class AdminModerationMetricsRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) {
    private fun day(column: String) = "FLOOR((EXTRACT(EPOCH FROM $column) + ${AdminQuery.KST_OFFSET_SECONDS}) / 86400)"
    private fun params(from: Instant, until: Instant) = mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC))

    fun daily(from: Instant, until: Instant): Map<LocalDate, AdminModerationMetricsDay> = jdbc.query("""
        SELECT metric_date,SUM(submitted) AS submitted,SUM(approved) AS approved,SUM(rejected) AS rejected,SUM(failed) AS failed
        FROM (
            SELECT ${day("created_at")} AS metric_date,COUNT(*) AS submitted,0 AS approved,0 AS rejected,0 AS failed
            FROM story_submissions WHERE created_at>=:from AND created_at<=:until GROUP BY ${day("created_at")}
            UNION ALL
            SELECT ${day("decided_at")} AS metric_date,0 AS submitted,
                SUM(CASE WHEN status='APPROVED' THEN 1 ELSE 0 END) AS approved,
                SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END) AS rejected,
                SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END) AS failed
            FROM story_submissions WHERE decided_at>=:from AND decided_at<=:until
                AND status IN ('APPROVED','REJECTED','FAILED') GROUP BY ${day("decided_at")}
        ) metrics GROUP BY metric_date
        """, params(from, until)) { rs, _ ->
        val date = LocalDate.ofEpochDay(rs.getLong("metric_date"))
        date to AdminModerationMetricsDay(date, rs.getLong("submitted"), rs.getLong("approved"), rs.getLong("rejected"), rs.getLong("failed"))
    }.toMap()

    fun backlog(): AdminModerationBacklog = jdbc.queryForObject("""
        SELECT COALESCE(SUM(CASE WHEN held_at IS NOT NULL THEN 1 ELSE 0 END),0) AS held,
            COALESCE(SUM(CASE WHEN held_at IS NULL THEN 1 ELSE 0 END),0) AS pending
        FROM story_submissions WHERE status='PENDING'
        """, emptyMap<String, Any>()) { rs, _ -> AdminModerationBacklog(rs.getLong("held"), rs.getLong("pending")) }!!

    fun rejectionRules(from: Instant, until: Instant): List<AdminModerationRejectionRule> {
        val counts = mutableMapOf<Pair<String, String>, Long>()
        // 기존 제출본 조회와 같은 Jackson 3 처리를 사용한다. 한 제출본씩 읽어 원문을 모아 두지 않는다.
        jdbc.query("SELECT issues FROM story_submissions WHERE status='REJECTED' AND decided_at>=:from AND decided_at<=:until",
            params(from, until), RowCallbackHandler { rs ->
                var node = mapper.readTree(rs.getString("issues"))
                if (node.isString) node = mapper.readTree(node.asText())
                node.toList().map { it.path("rule").asText() to it.path("type").asText() }.toSet().forEach { key ->
                    counts[key] = counts.getOrDefault(key, 0L) + 1
                }
            })
        return counts.map { (key, count) -> AdminModerationRejectionRule(key.first, key.second, count) }
            .sortedWith(compareByDescending<AdminModerationRejectionRule> { it.submissions }.thenBy { it.rule }.thenBy { it.type })
    }
}
