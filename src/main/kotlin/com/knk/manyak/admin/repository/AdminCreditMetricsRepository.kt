package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class AdminCreditPeriodAmounts(val grantedByReason: List<AdminCreditReasonAmount>, val spentByReason: List<AdminCreditReasonAmount>)

@Repository
class AdminCreditMetricsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val grantReasons = listOf("SIGNUP_REWARD", "INVITE_REWARD", "ATTENDANCE_REWARD", "PURCHASE", "ADMIN_GRANT")
    private val spendReasons = listOf("STORY_CREATION", "CHAT_TURN", "CHAT_IMAGE")
    private fun owner(column: String, excluded: List<Long>) =
        if (excluded.isEmpty()) "1=1" else "($column IS NULL OR $column NOT IN (:excluded))"
    private fun excludedParams(excluded: List<Long>): Map<String, Any> = if (excluded.isEmpty()) emptyMap() else mapOf("excluded" to excluded)

    fun period(from: Instant, until: Instant, excluded: List<Long>): AdminCreditPeriodAmounts {
        val rows = jdbc.query("""SELECT CAST(reason AS VARCHAR) AS reason,
            SUM(CASE WHEN ${AdminQuery.GRANTED} THEN amount ELSE 0 END) AS granted,
            SUM(CASE WHEN ${AdminQuery.SPENT} THEN -amount ELSE 0 END) AS spent
            FROM credit_transactions WHERE created_at>=:from AND created_at<=:until AND ${owner("user_id", excluded)}
                AND ((${AdminQuery.GRANTED}) OR (${AdminQuery.SPENT})) GROUP BY reason""",
            mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC)) + excludedParams(excluded)) { rs, _ ->
            rs.getString("reason") to (rs.getLong("granted") to rs.getLong("spent"))
        }.toMap()
        return AdminCreditPeriodAmounts(grantReasons.map { AdminCreditReasonAmount(it, rows[it]?.first ?: 0L) },
            spendReasons.map { AdminCreditReasonAmount(it, rows[it]?.second ?: 0L) })
    }

    fun balances(now: Instant, excluded: List<Long>): AdminCreditBalances = jdbc.queryForObject("""
        SELECT COUNT(*) AS members, COALESCE(SUM(member_total),0) AS total,
            COALESCE(SUM(CASE WHEN member_total=0 THEN 1 ELSE 0 END),0) AS zero_members,
            COALESCE(SUM(CASE WHEN member_total BETWEEN 1 AND 99 THEN 1 ELSE 0 END),0) AS small_members,
            COALESCE(SUM(CASE WHEN member_total BETWEEN 100 AND 499 THEN 1 ELSE 0 END),0) AS medium_members,
            COALESCE(SUM(CASE WHEN member_total BETWEEN 500 AND 1999 THEN 1 ELSE 0 END),0) AS large_members,
            COALESCE(SUM(CASE WHEN member_total>=2000 THEN 1 ELSE 0 END),0) AS high_members,
            COALESCE(SUM(purchased),0) AS purchased,COALESCE(SUM(rewarded),0) AS rewarded,
            COALESCE(SUM(legacy),0) AS legacy,COALESCE(SUM(expiring),0) AS expiring
        FROM (
            SELECT u.id,COALESCE(SUM(l.remaining),0) AS member_total,
                COALESCE(SUM(CASE WHEN CAST(t.reason AS VARCHAR)='PURCHASE' THEN l.remaining ELSE 0 END),0) AS purchased,
                COALESCE(SUM(CASE WHEN l.transaction_id IS NULL OR CAST(t.reason AS VARCHAR)='PURCHASE' THEN 0 ELSE l.remaining END),0) AS rewarded,
                COALESCE(SUM(CASE WHEN l.transaction_id IS NULL THEN l.remaining ELSE 0 END),0) AS legacy,
                COALESCE(SUM(CASE WHEN l.expires_at<=:expiringUntil THEN l.remaining ELSE 0 END),0) AS expiring
            FROM users u LEFT JOIN credit_lots l ON l.user_id=u.id AND l.remaining>0 AND (l.expires_at IS NULL OR l.expires_at>:now)
            LEFT JOIN credit_transactions t ON t.id=l.transaction_id
            WHERE u.status<>'DELETED' AND u.deleted_at IS NULL AND ${owner("u.id", excluded)} GROUP BY u.id
        ) member_balances
        """, mapOf("now" to now.atOffset(ZoneOffset.UTC),
        "expiringUntil" to now.plus(30, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC)) + excludedParams(excluded)) { rs, _ ->
        AdminCreditBalances(rs.getLong("members"), rs.getLong("total"), listOf(
            AdminCreditBalanceBucket("0", rs.getLong("zero_members")), AdminCreditBalanceBucket("1~99", rs.getLong("small_members")),
            AdminCreditBalanceBucket("100~499", rs.getLong("medium_members")), AdminCreditBalanceBucket("500~1999", rs.getLong("large_members")),
            AdminCreditBalanceBucket("2000+", rs.getLong("high_members"))),
            rs.getLong("purchased"), rs.getLong("rewarded"), rs.getLong("legacy"), rs.getLong("expiring"))
    }!!
}
