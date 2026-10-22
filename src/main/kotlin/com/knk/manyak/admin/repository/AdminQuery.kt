package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.*
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 값은 모두 바인딩하고 SQL 식은 서버의 허용 목록으로만 조립한다. */
internal class AdminQuery {
    val params = mutableMapOf<String, Any>()
    private val conditions = mutableListOf<String>()
    fun add(sql: String) { conditions += sql }
    fun equal(column: String, value: Any?) { value?.let { val key = "p${params.size}"; params[key] = it; add("$column = :$key") } }
    fun range(column: String, from: String?, to: String?) {
        val range = AdminDateRange.of(from, to)
        range.from?.let { bound(column, ">=", it) }
        range.until?.let { bound(column, "<", it) }
    }
    private fun bound(column: String, operator: String, instant: Instant) {
        val key = "p${params.size}"; params[key] = instant.atOffset(ZoneOffset.UTC); add("$column $operator :$key")
    }
    fun search(q: String?, ids: List<String>, texts: List<String>, exact: List<String> = emptyList()) {
        if (q == null) return
        val uuid = runCatching { UUID.fromString(q) }.getOrNull()?.takeIf { it.toString() == q }
        if (uuid != null) {
            params["searchId"] = uuid
            params["search"] = q
            add((ids.map { "$it = :searchId" } + exact.map { "${normalized(it)} = :search" }).joinToString(" OR ", "(", ")"))
        } else {
            params["search"] = q
            add((texts.map { "POSITION(:search IN ${normalized(it)}) > 0" } + exact.map { "${normalized(it)} = :search" }).joinToString(" OR ", "(", ")"))
        }
    }
    fun ai(request: AdminAiRequest, relation: String) {
        if (request.aiFeature == null && request.aiStatus == null && request.aiFrom == null && request.aiTo == null && request.hasLatency == null) return
        val ai = AdminQuery()
        ai.add(relation)
        ai.equal("al.feature", request.aiFeature)
        if (request.aiStatus == "FINISHED") ai.add("al.status IN ('SUCCEEDED', 'FAILED')") else ai.equal("al.status", request.aiStatus)
        request.hasLatency?.let { ai.add("al.latency_ms IS ${if (it) "NOT " else ""}NULL") }
        ai.range("al.created_at", request.aiFrom, request.aiTo)
        // 이 하위 조건의 바인딩 이름을 부모와 분리한다.
        var sql = ai.where()
        ai.params.forEach { (key, value) -> sql = sql.replace(":$key", ":ai_$key"); params["ai_$key"] = value }
        add("EXISTS (SELECT 1 FROM ai_call_logs al WHERE $sql)")
    }
    fun where() = conditions.joinToString(" AND ").ifEmpty { "1=1" }
    companion object {
        /** KST는 DST가 없어 고정 오프셋 에포크 일수로 날짜를 묶는다. AT TIME ZONE 뒤 DATE 캐스트는 DB 세션 시간대에 좌우된다(H2·UTC 세션). */
        val KST_OFFSET_SECONDS: Int = java.time.ZoneId.of("Asia/Seoul").rules.getOffset(Instant.EPOCH).totalSeconds
        const val SPENT = "amount < 0 AND CAST(reason AS VARCHAR) IN ('STORY_CREATION','CHAT_TURN','CHAT_IMAGE')"
        const val GRANTED = "amount > 0 AND CAST(reason AS VARCHAR) IN ('SIGNUP_REWARD','INVITE_REWARD','ATTENDANCE_REWARD','PURCHASE','ADMIN_GRANT')"
        /** 웹의 빈 인물 칸은 제외한다. 인물 태그는 EXISTS로 판정해 태그가 여러 개여도 한 명이다. */
        fun inputSupportingCharacter(alias: String): String {
            // 입력 DTO의 Unicode 공백 처리와 맞추며 PostgreSQL·H2 양쪽에서 같은 문자를 제거한다.
            val whitespace = "[\\s\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+"
            return """($alias.role='SUPPORTING_CHARACTER' AND (
                REGEXP_REPLACE(COALESCE($alias.name, ''), '$whitespace', '', 'g') <> ''
                OR $alias.gender IS NOT NULL
                OR EXISTS (SELECT 1 FROM story_creation_session_tags ct
                    WHERE ct.character_id=$alias.id AND ct.creation_session_id=$alias.creation_session_id)))"""
        }
        fun normalized(column: String) = "LOWER(TRIM(REGEXP_REPLACE($column, '\\s+', ' ', 'g')))"
        val turnBounds = listOf(0..0, 1..1, 2..2, 3..4, 5..6, 7..9, 10..14, 15..19, 20..29, 30..49, 50..Int.MAX_VALUE)
        val memberBounds = listOf(1..1, 2..2, 3..4, 5..9, 10..Int.MAX_VALUE)
    }
}
internal fun ResultSet.instant(name: String): Instant? = getTimestamp(name)?.toInstant()
internal fun ResultSet.longOrNull(name: String): Long? = (getObject(name) as? Number)?.toLong()
internal fun <T> NamedParameterJdbcTemplate.adminPage(select: String, from: String, query: AdminQuery, page: AdminPagination,
    id: String, mapper: RowMapper<T>): AdminPageResponse<T> {
    val total = queryForObject("SELECT COUNT(*) $from WHERE ${query.where()}", query.params, Long::class.java)!!
    if (page.offset >= total) return page.response(emptyList(), total)
    return page.response(query("SELECT $select $from WHERE ${query.where()} ORDER BY ${page.orderBy}, $id ASC LIMIT :size OFFSET :offset",
        query.params + mapOf("size" to page.size, "offset" to page.offset), mapper), total)
}
