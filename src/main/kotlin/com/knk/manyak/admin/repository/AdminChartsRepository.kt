package com.knk.manyak.admin.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 기간으로 제한한 집계만 읽는다. 원문·대화 설정을 선택하는 쿼리는 없다. */
@Repository
class AdminChartsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun daily(from: Instant, now: Instant, excluded: List<Long>): Map<LocalDate, Map<String, Long>> {
        val p = params(from, now, excluded)
        val result = mutableMapOf<LocalDate, MutableMap<String, Long>>()
        fun read(table: String, column: String, owner: String, metrics: Map<String, String>, extra: String = "1=1") {
            val day = "FLOOR((EXTRACT(EPOCH FROM $column) + ${AdminQuery.KST_OFFSET_SECONDS}) / 86400)"
            val select = metrics.entries.joinToString(",") { "${it.value} AS ${it.key}" }
            jdbc.query("SELECT $day AS metric_date, $select FROM $table WHERE ${during(column)} AND ${owner(owner, excluded)} AND $extra GROUP BY $day", p) { rs ->
                val values = result.getOrPut(LocalDate.ofEpochDay(rs.getLong("metric_date"))) { mutableMapOf() }
                metrics.keys.forEach { values[it] = rs.getLong(it) }
            }
        }
        read("users", "created_at", "id", mapOf("joined" to "COUNT(*)"))
        read("story_chats", "created_at", "user_id", mapOf("chats" to "COUNT(*)"))
        read("story_messages m JOIN story_chats c ON c.id=m.chat_id", "m.created_at", "c.user_id", mapOf("turns" to "COUNT(*)"), "m.role='ASSISTANT'")
        read("stories", "created_at", "user_id", mapOf("stories" to "COUNT(*)"))
        read("credit_transactions", "created_at", "user_id", mapOf(
            "granted" to "SUM(CASE WHEN ${AdminQuery.GRANTED} THEN amount ELSE 0 END)",
            "spent" to "SUM(CASE WHEN ${AdminQuery.SPENT} THEN -amount ELSE 0 END)",
            "attendance" to "COUNT(DISTINCT CASE WHEN CAST(reason AS VARCHAR)='ATTENDANCE_REWARD' THEN user_id END)"))
        read("credit_orders", "completed_at", "user_id", mapOf("payments" to "COUNT(*)", "revenue" to "SUM(price_krw)"), "completed_at IS NOT NULL")
        return result
    }
    fun distributions(from: Instant, now: Instant, excluded: List<Long>, officialId: Long?): List<List<Map<String, Any?>>> {
        val p = params(from, now, excluded)
        // 공통 분포는 스토리 삭제 여부와 무관한 미삭제 채팅을 읽는다. 일별 추이에는 삭제 채팅도 포함된다.
        // 장르 깊이만 링크 대상인 스토리 목록에 맞춰 삭제 스토리를 제외한다.
        val chats = """FROM story_chats c JOIN stories s ON s.id=c.story_id
            WHERE c.deleted_at IS NULL AND ${during("c.created_at")} AND ${owner("c.user_id", excluded)}"""
        fun query(sql: String) = jdbc.queryForList(sql, p)
        val genre = query("SELECT s.genre AS label, SUM(c.current_turn) AS turns, COUNT(*) AS n $chats AND s.deleted_at IS NULL GROUP BY s.genre ORDER BY s.genre")
        val bucket = "CASE " + AdminQuery.turnBounds.mapIndexed { i, r -> "WHEN c.current_turn BETWEEN ${r.first} AND ${r.last} THEN $i" }.joinToString(" ") + " END"
        val turns = query("SELECT $bucket AS bucket, COUNT(*) AS n $chats GROUP BY $bucket")
        val started = query("SELECT CASE WHEN c.user_id IS NULL THEN 'GUEST' ELSE 'MEMBER' END AS label, COUNT(*) AS n $chats GROUP BY CASE WHEN c.user_id IS NULL THEN 'GUEST' ELSE 'MEMBER' END")
        val memberCase = "CASE WHEN n=1 THEN 0 WHEN n=2 THEN 1 WHEN n<=4 THEN 2 WHEN n<=9 THEN 3 ELSE 4 END"
        val members = query("SELECT $memberCase AS bucket, COUNT(*) AS n FROM (SELECT c.user_id, COUNT(*) AS n $chats AND c.user_id IS NOT NULL GROUP BY c.user_id) t GROUP BY $memberCase")
        val waitsFrom = """FROM (SELECT c.id, c.created_at $chats) c
            LEFT JOIN story_messages m ON m.chat_id=c.id AND m.role='USER' GROUP BY c.id, c.created_at"""
        val waits = query("""SELECT AVG(CASE WHEN seconds<=900 THEN seconds END) AS average,
            PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY CASE WHEN seconds<=900 THEN seconds END) AS p50,
            PERCENTILE_CONT(0.75) WITHIN GROUP (ORDER BY CASE WHEN seconds<=900 THEN seconds END) AS p75,
            PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY CASE WHEN seconds<=900 THEN seconds END) AS p90,
            SUM(CASE WHEN seconds>900 THEN 1 ELSE 0 END) AS overdue,
            SUM(CASE WHEN seconds IS NULL THEN 1 ELSE 0 END) AS missing
            FROM (SELECT EXTRACT(EPOCH FROM (MIN(m.created_at)-c.created_at)) AS seconds $waitsFrom) waits""")
        // 제작 기능은 로그의 story_id로 소유자를 판정한다. story_id가 없는 제작 호출도 포함한다.
        val ai = """FROM ai_call_logs al LEFT JOIN story_chats c ON c.public_id=al.chat_id LEFT JOIN stories s ON s.id=al.story_id
            WHERE ${during("al.created_at")} AND (
            (al.feature IN ('chat_response','choice_generation','chat_image') AND c.id IS NOT NULL AND ${owner("c.user_id", excluded)}) OR
            (al.feature IN ('storyline_generation','story_completion') AND ${owner("s.user_id", excluded)}))"""
        val latency = query("SELECT al.feature AS label, AVG(al.latency_ms/1000.0) AS metric_value $ai AND al.status='SUCCEEDED' AND al.latency_ms IS NOT NULL GROUP BY al.feature")
        val success = query("""SELECT al.feature AS label, 100.0*SUM(CASE WHEN al.status='SUCCEEDED' THEN 1 ELSE 0 END)/COUNT(*) AS metric_value
            $ai AND al.status IN ('SUCCEEDED','FAILED') GROUP BY al.feature""")
        val sessionExcluded = excluded.filter { it != officialId }
        val sessionParams = params(from, now, sessionExcluded)
        val characters = jdbc.queryForList("""SELECT n AS bucket, COUNT(*) AS n FROM (
            SELECT cs.id, COUNT(ch.id) AS n FROM story_creation_sessions cs
            JOIN stories s ON s.id=cs.story_id AND s.deleted_at IS NULL
            LEFT JOIN story_creation_characters ch
            ON ch.creation_session_id=cs.id AND ${AdminQuery.inputSupportingCharacter("ch")}
            WHERE cs.status='STORY_CREATED'
            AND cs.id=(SELECT MAX(x.id) FROM story_creation_sessions x WHERE x.story_id=cs.story_id AND x.status='STORY_CREATED')
            AND ${during("cs.created_at")} AND ${owner("cs.user_id", sessionExcluded)} GROUP BY cs.id
            ) t GROUP BY n""", sessionParams)
        return listOf(genre, turns, started, members, waits, latency, success, characters)
    }
    private fun params(from: Instant, now: Instant, excluded: List<Long>): Map<String, Any> =
        mapOf("from" to from.atOffset(ZoneOffset.UTC), "now" to now.atOffset(ZoneOffset.UTC)) + if (excluded.isEmpty()) emptyMap() else mapOf("excluded" to excluded)
    private fun during(column: String) = "$column >= :from AND $column <= :now"
    private fun owner(column: String, excluded: List<Long>) = if (excluded.isEmpty()) "1=1" else "($column IS NULL OR $column NOT IN (:excluded))"
}
