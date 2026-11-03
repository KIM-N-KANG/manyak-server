package com.knk.manyak.admin.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AdminActivityHoursCount(val weekday: Int, val hour: Int, val count: Long)
data class AdminActivityHoursRows(
    val signups: List<AdminActivityHoursCount>, val messages: List<AdminActivityHoursCount>,
    val stories: List<AdminActivityHoursCount>, val payments: List<AdminActivityHoursCount>,
)

@Repository
class AdminActivityHoursRepository(private val jdbc: NamedParameterJdbcTemplate) {
    // 요약·차트와 같은 제외 조건이며 게스트 소유 행을 남긴다.
    private fun owner(column: String, excluded: List<Long>) =
        if (excluded.isEmpty()) "1=1" else "($column IS NULL OR $column NOT IN (:excluded))"

    fun counts(from: Instant, until: Instant, excluded: List<Long>): AdminActivityHoursRows {
        val params = mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC)) +
            if (excluded.isEmpty()) emptyMap() else mapOf("excluded" to excluded)
        fun read(table: String, timestamp: String, ownerColumn: String, extra: String = "1=1"): List<AdminActivityHoursCount> {
            val hour = "FLOOR((EXTRACT(EPOCH FROM $timestamp) + ${AdminQuery.KST_OFFSET_SECONDS}) / 3600)"
            return jdbc.query("""SELECT $hour AS kst_hour,COUNT(*) AS n FROM $table
                WHERE $timestamp>=:from AND $timestamp<=:until AND ${owner(ownerColumn, excluded)} AND $extra
                GROUP BY $hour""", params) { rs, _ ->
                val epochHour = rs.getLong("kst_hour")
                val date = LocalDate.ofEpochDay(Math.floorDiv(epochHour, 24L))
                AdminActivityHoursCount(date.dayOfWeek.value, Math.floorMod(epochHour, 24L).toInt(), rs.getLong("n"))
            }
        }
        return AdminActivityHoursRows(
            read("users u", "u.created_at", "u.id", "EXISTS (SELECT 1 FROM social_accounts sa WHERE sa.user_id=u.id)"),
            read("story_messages m JOIN story_chats c ON c.id=m.chat_id", "m.created_at", "c.user_id", "m.role='USER' AND ${AdminQuery.generatedUser()}"),
            read("stories s", "s.created_at", "s.user_id"),
            read("credit_orders o", "o.completed_at", "o.user_id"),
        )
    }
}
