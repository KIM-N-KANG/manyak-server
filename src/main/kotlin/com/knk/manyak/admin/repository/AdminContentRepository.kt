package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.*
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class AdminContentRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val session = "LEFT JOIN story_creation_sessions cs ON cs.id=(SELECT MAX(x.id) FROM story_creation_sessions x WHERE x.story_id=s.id AND x.status='STORY_CREATED')"
    private val supporting = "(SELECT COUNT(*) FROM story_creation_characters ch WHERE ch.creation_session_id=cs.id AND ${AdminQuery.inputSupportingCharacter("ch")})"
    private val held = "EXISTS (SELECT 1 FROM story_submissions sub WHERE sub.story_id=s.id AND sub.status='PENDING' AND sub.held_at IS NOT NULL)"

    fun stories(request: AdminStoriesRequest, page: AdminPagination, officialId: Long?, storyId: UUID? = null): AdminPageResponse<AdminStoryListResponse> {
        val q = AdminQuery()
        q.deleted("s.deleted_at", request.deleted); q.equal("s.public_id", storyId); q.equal("u.public_id", request.memberId)
        q.equal("s.visibility", request.visibility)
        request.scope?.let {
            q.add(if (officialId == null) { if (it == "original") "1=0" else "1=1" }
                else if (it == "original") "s.user_id=$officialId" else "(s.user_id IS NULL OR s.user_id<>$officialId)")
        }
        request.genre?.let { q.params["genre"] = ", $it, "; q.add("POSITION(:genre IN CONCAT(', ', s.genre, ', ')) > 0") }
        request.moderationStatus?.let { q.add(if (it == "HELD") held else "NOT $held") }
        q.range("s.created_at", request.from, request.to)
        q.range("cs.created_at", request.creationFrom, request.creationTo)
        // 제작 방식은 스토리를 완성한(STORY_CREATED) 제작 세션 유무다. session 조인이 그 최신 세션을 붙인다.
        request.creationMode?.let { q.add(if (it == "SIMPLE") "cs.id IS NOT NULL" else "cs.id IS NULL") }
        request.supportingCharacterCount?.let { q.add("cs.id IS NOT NULL AND $supporting=$it") }
        q.search(request.normalizedQuery(), listOf("s.public_id"), listOf("s.title", "u.nickname"))
        q.ai(request, "al.story_id=s.id")
        val period = AdminQuery().apply { range("c.created_at", request.chatFrom, request.chatTo) }
        var periodSql = period.where()
        period.params.forEach { (key, value) -> periodSql = periodSql.replace(":$key", ":chat_$key"); q.params["chat_$key"] = value }
        val chatCondition = "c.story_id=s.id AND c.deleted_at IS NULL AND $periodSql"
        val count = "(SELECT COUNT(*) FROM story_chats c WHERE $chatCondition)"
        val turns = "(SELECT COALESCE(SUM(c.current_turn),0) FROM story_chats c WHERE $chatCondition)"
        if (request.chatFrom != null || request.chatTo != null) q.add("$count > 0")
        val from = "FROM stories s LEFT JOIN users u ON u.id=s.user_id LEFT JOIN admin_accounts a ON a.user_id=u.id $session"
        val result = jdbc.adminPage("""s.public_id, s.title, s.genre, s.description, s.visibility, s.created_at, s.deleted_at, s.taken_down_at, s.takedown_reason, s.takedown_reason_detail, s.user_id AS owner_internal_id,
            CASE WHEN $held THEN 'HELD' ELSE 'APPROVED' END AS moderation_status,
            CASE WHEN cs.id IS NULL THEN NULL ELSE $supporting END AS supporting_count,
            $count AS chat_count, $turns AS total_turns, $MEMBER_COLUMNS""", from, q,
            page, "s.public_id", RowMapper { rs, _ ->
                val owner = memberSummary(rs)
                AdminStoryListResponse(rs.getObject("public_id", UUID::class.java), rs.getString("title"), (rs.getString("genre") ?: ""), rs.getString("description"),
                    if (officialId != null && rs.longOrNull("owner_internal_id") == officialId) "original" else "user",
                    rs.getString("visibility"), rs.getString("moderation_status"), emptyList(), rs.longOrNull("supporting_count"), owner,
                    rs.getLong("chat_count"), rs.getLong("total_turns"), rs.instant("created_at")!!, rs.instant("deleted_at"),
                    rs.instant("taken_down_at"), rs.getString("takedown_reason"), rs.getString("takedown_reason_detail"))
            })
        if (result.items.isEmpty()) return result
        val tags = jdbc.query("""SELECT s.public_id, t.name FROM stories s $session
            JOIN story_creation_session_tags st ON st.creation_session_id=cs.id JOIN story_creation_tags t ON t.id=st.tag_id
            WHERE s.public_id IN (:ids) ORDER BY CASE t.tag_type WHEN 'GENRE' THEN 0 WHEN 'PROTAGONIST' THEN 1 ELSE 2 END, t.sort_order, t.id""",
            mapOf("ids" to result.items.map { it.storyId }), RowMapper { rs, _ -> rs.getObject("public_id", UUID::class.java) to rs.getString("name") })
            .groupBy({ it.first }, { it.second })
        return result.copy(items = result.items.map { it.copy(tags = tags[it.storyId] ?: emptyList()) })
    }
    fun retention(storyId: UUID): List<AdminRetention> {
        val columns = AdminQuery.turnReachThresholds.joinToString(",") { "SUM(CASE WHEN c.current_turn >= $it THEN 1 ELSE 0 END) AS n$it" }
        val row = jdbc.queryForMap("SELECT COUNT(*) AS total, $columns FROM story_chats c JOIN stories s ON s.id=c.story_id WHERE s.public_id=:id AND c.deleted_at IS NULL", mapOf("id" to storyId))
        val total = (row["total"] as Number).toDouble()
        return AdminQuery.turnReachThresholds.map { AdminRetention(it, if (total == 0.0) 0.0 else (row["n$it"] as Number).toDouble() / total) }
    }

    private val firstAt = "(SELECT MIN(m.created_at) FROM story_messages m WHERE m.chat_id=c.id AND m.role='USER')"
    // EXTRACT(EPOCH FROM timestamp subtraction)는 H2 PostgreSQL 모드와 PostgreSQL에서 같은 초 값을 낸다.
    private val firstSeconds = "EXTRACT(EPOCH FROM ($firstAt - c.created_at))"
    private val credits = """(SELECT COALESCE(SUM(-t.amount),0) FROM credit_transactions t WHERE t.ref_id=c.id
        AND t.ref_type IN ('CHAT','CHAT_TURN','CHAT_IMAGE') AND t.amount<0 AND CAST(t.reason AS VARCHAR) IN ('CHAT_TURN','CHAT_IMAGE'))"""
    fun chats(request: AdminChatsRequest, page: AdminPagination, chatId: UUID? = null): AdminPageResponse<AdminChatResponse> {
        val q = AdminQuery()
        q.deleted("c.deleted_at", request.deleted); q.equal("c.public_id", chatId); q.equal("s.public_id", request.storyId); q.equal("u.public_id", request.memberId)
        request.startedAs?.let { q.add("c.user_id IS ${if (it == "MEMBER") "NOT " else ""}NULL") }
        request.turnBucket?.let { val range = AdminQuery.turnBounds[it]; q.add("c.current_turn BETWEEN ${range.first} AND ${range.last}") }
        request.hasFirstMessage?.let { q.add(if (it) "$firstSeconds <= 900" else "($firstSeconds IS NULL OR $firstSeconds > 900)") }
        request.turnDate?.let {
            val date = AdminQuery().apply { range("m.created_at", it, it) }
            var sql = date.where()
            date.params.forEach { (key, value) -> sql = sql.replace(":$key", ":turn_$key"); q.params["turn_$key"] = value }
            q.add("EXISTS (SELECT 1 FROM story_messages m WHERE m.chat_id=c.id AND m.role='ASSISTANT' AND $sql)")
        }
        q.range("c.created_at", request.from, request.to)
        q.search(request.normalizedQuery(), listOf("c.public_id"), listOf("s.title", "u.nickname"))
        q.ai(request, "al.chat_id=c.public_id")
        return jdbc.adminPage("""c.public_id, s.public_id AS story_public_id, s.title, s.genre, c.current_turn, c.created_at, c.updated_at, c.deleted_at,
            $credits AS credits_spent, $firstSeconds AS first_seconds, $MEMBER_COLUMNS""",
            "FROM story_chats c JOIN stories s ON s.id=c.story_id LEFT JOIN users u ON u.id=c.user_id LEFT JOIN admin_accounts a ON a.user_id=u.id",
            q, page, "c.public_id", RowMapper { rs: ResultSet, _ ->
                val member = memberSummary(rs)
                AdminChatResponse(rs.getObject("public_id", UUID::class.java), AdminStorySummary(rs.getObject("story_public_id", UUID::class.java), rs.getString("title"), (rs.getString("genre") ?: "")),
                    member, if (member == null) "GUEST" else "MEMBER", rs.getInt("current_turn"), rs.instant("created_at")!!, rs.instant("updated_at")!!,
                    rs.getLong("credits_spent"), rs.longOrNull("first_seconds"), rs.instant("deleted_at"))
            })
    }
}
