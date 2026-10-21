package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

@Repository
class AdminDashboardRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun summary(now: Instant, today: Instant, excluded: List<Long>): AdminDashboardSummaryResponse {
        val params = mutableMapOf<String, Any>("today" to today.atOffset(ZoneOffset.UTC), "now" to now.atOffset(ZoneOffset.UTC))
        if (excluded.isNotEmpty()) params["excluded"] = excluded
        fun owner(column: String): String = if (excluded.isEmpty()) "1=1" else "($column IS NULL OR $column NOT IN (:excluded))"
        fun during(column: String) = "$column >= :today AND $column <= :now"
        fun sums(select: String, from: String) = jdbc.queryForMap("SELECT $select $from", params)
        fun Map<String, Any?>.number(key: String) = (this[key] as? Number)?.toLong() ?: 0L
        fun counts(table: String, ownerColumn: String, extra: String = "1=1", createdColumn: String = "created_at"): Pair<Long, Long> {
            val row = sums("COUNT(*) AS total, SUM(CASE WHEN ${during(createdColumn)} THEN 1 ELSE 0 END) AS today",
                "FROM $table WHERE $extra AND ${owner(ownerColumn)}")
            return row.number("total") to row.number("today")
        }
        val members = sums("""SUM(CASE WHEN status <> 'DELETED' THEN 1 ELSE 0 END) AS total,
            SUM(CASE WHEN ${during("created_at")} THEN 1 ELSE 0 END) AS today""", "FROM users WHERE ${owner("id")}")
        val chats = counts("story_chats", "user_id")
        val turns = counts("story_messages m JOIN story_chats c ON c.id = m.chat_id", "c.user_id", "m.role = 'ASSISTANT'", "m.created_at")
        val stories = counts("stories", "user_id")
        val grant = "amount > 0 AND CAST(reason AS VARCHAR) IN ('SIGNUP_REWARD', 'INVITE_REWARD', 'ATTENDANCE_REWARD', 'PURCHASE', 'ADMIN_GRANT')"
        val spent = "amount < 0 AND CAST(reason AS VARCHAR) IN ('STORY_CREATION', 'CHAT_TURN', 'CHAT_IMAGE')"
        val credits = sums("""SUM(CASE WHEN $grant THEN amount ELSE 0 END) AS granted_total,
            SUM(CASE WHEN $grant AND ${during("created_at")} THEN amount ELSE 0 END) AS granted_today,
            SUM(CASE WHEN $spent THEN -amount ELSE 0 END) AS spent_total,
            SUM(CASE WHEN $spent AND ${during("created_at")} THEN -amount ELSE 0 END) AS spent_today""",
            "FROM credit_transactions WHERE ${owner("user_id")}")
        val payments = sums("""COUNT(*) AS total,
            SUM(CASE WHEN ${during("completed_at")} THEN 1 ELSE 0 END) AS today,
            SUM(price_krw) AS amount_total,
            SUM(CASE WHEN ${during("completed_at")} THEN price_krw ELSE 0 END) AS amount_today""",
            "FROM credit_orders WHERE completed_at IS NOT NULL AND ${owner("user_id")}")
        return AdminDashboardSummaryResponse(now, today,
            AdminMemberMetrics(members.number("total"), members.number("today")),
            AdminChatMetrics(chats.first, chats.second, turns.first, turns.second),
            AdminStoryMetrics(stories.first, stories.second),
            AdminCreditMetrics(credits.number("granted_total"), credits.number("granted_today"), credits.number("spent_total"), credits.number("spent_today")),
            AdminPaymentMetrics(payments.number("total"), payments.number("today"), payments.number("amount_total"), payments.number("amount_today")))
    }
}
