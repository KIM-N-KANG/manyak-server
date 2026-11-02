package com.knk.manyak.admin.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class AdminChatActivity(val date: LocalDate, val userId: Long, val chatId: Long)

/** USER 활동은 날짜·회원·채팅 쌍으로 축약하고, 턴과 판본은 날짜별 수만 읽는다. */
@Repository
class AdminChatEngagementRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private fun day(column: String) = "FLOOR((EXTRACT(EPOCH FROM $column) + ${AdminQuery.KST_OFFSET_SECONDS}) / 86400)"
    private fun owner(excluded: List<Long>) =
        if (excluded.isEmpty()) "1=1" else "(c.user_id IS NULL OR c.user_id NOT IN (:excluded))"
    private fun params(from: Instant, until: Instant, excluded: List<Long>): Map<String, Any> =
        mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC)) +
            if (excluded.isEmpty()) emptyMap() else mapOf("excluded" to excluded)

    fun activity(from: Instant, until: Instant, excluded: List<Long>): List<AdminChatActivity> = jdbc.query("""
        SELECT ${day("m.created_at")} AS metric_date,c.user_id,c.id AS chat_id
        FROM story_messages m JOIN story_chats c ON c.id=m.chat_id
        WHERE m.role='USER' AND m.created_at>=:from AND m.created_at<=:until
            AND c.user_id IS NOT NULL AND ${owner(excluded)}
        GROUP BY ${day("m.created_at")},c.user_id,c.id
        """, params(from, until, excluded)) { rs, _ ->
        AdminChatActivity(LocalDate.ofEpochDay(rs.getLong("metric_date")), rs.getLong("user_id"), rs.getLong("chat_id"))
    }

    fun turns(from: Instant, until: Instant, excluded: List<Long>): Map<LocalDate, Long> = jdbc.query("""
        SELECT ${day("m.created_at")} AS metric_date,COUNT(*) AS n
        FROM story_messages m JOIN story_chats c ON c.id=m.chat_id
        WHERE m.role='ASSISTANT' AND m.created_at>=:from AND m.created_at<=:until AND ${owner(excluded)}
        GROUP BY ${day("m.created_at")}
        """, params(from, until, excluded)) { rs, _ -> LocalDate.ofEpochDay(rs.getLong("metric_date")) to rs.getLong("n") }.toMap()

    // 판본 보관은 성공 교체 트랜잭션에서만 발생한다. 원본 메시지 생성일·채팅 누적 카운터를 사용하지 않는다.
    fun regenerations(from: Instant, until: Instant, excluded: List<Long>): Map<LocalDate, Long> = jdbc.query("""
        SELECT ${day("v.created_at")} AS metric_date,COUNT(*) AS n
        FROM story_message_versions v JOIN story_messages m ON m.id=v.message_id JOIN story_chats c ON c.id=m.chat_id
        WHERE v.created_at>=:from AND v.created_at<=:until AND ${owner(excluded)}
        GROUP BY ${day("v.created_at")}
        """, params(from, until, excluded)) { rs, _ -> LocalDate.ofEpochDay(rs.getLong("metric_date")) to rs.getLong("n") }.toMap()
}
