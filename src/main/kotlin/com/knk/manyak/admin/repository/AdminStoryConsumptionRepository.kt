package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.AdminStoryConsumptionRank
import com.knk.manyak.admin.dto.AdminStoryCreators
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal data class AdminStoryConsumptionCounts(
    val publicStories: Long, val officialStories: Long, val playedStories: Long,
    val otherMemberChats: Long, val topChats: Long,
)

@Repository
class AdminStoryConsumptionRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private fun owner(column: String, excluded: List<Long>) =
        if (excluded.isEmpty()) "1=1" else "$column NOT IN (:excluded)"
    // 공식 계정의 콘텐츠는 팀 제외의 예외다. 창작자와 채팅 소유자는 일반 팀 제외를 적용한다.
    private fun storyOwner(excluded: List<Long>) =
        if (excluded.isEmpty()) "1=1" else "(s.user_id NOT IN (:excluded) OR s.user_id=:officialId)"
    private fun published(alias: String) = "$alias.status='PUBLISHED' AND $alias.visibility='PUBLIC' AND $alias.deleted_at IS NULL AND $alias.user_id IS NOT NULL"
    private fun params(from: Instant, until: Instant, excluded: List<Long>, officialId: Long?) =
        mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC), "officialId" to officialId) +
            if (excluded.isEmpty()) emptyMap() else mapOf("excluded" to excluded)

    private fun consumption(excluded: List<Long>) = """WITH consumption AS (
        SELECT s.id,s.public_id,s.title,s.user_id,s.status,s.visibility,s.deleted_at,
            COUNT(*) AS chats,COUNT(DISTINCT c.user_id) AS players,SUM(CAST(c.current_turn AS BIGINT)) AS turns
        FROM stories s JOIN story_chats c ON c.story_id=s.id
        WHERE c.created_at>=:from AND c.created_at<=:until AND c.deleted_at IS NULL
            AND c.user_id IS NOT NULL AND c.user_id<>s.user_id
            AND ${owner("c.user_id", excluded)} AND ${storyOwner(excluded)}
        GROUP BY s.id,s.public_id,s.title,s.user_id,s.status,s.visibility,s.deleted_at
    )"""

    internal fun counts(from: Instant, until: Instant, excluded: List<Long>, officialId: Long?): AdminStoryConsumptionCounts =
        jdbc.queryForObject("""${consumption(excluded)} SELECT
            (SELECT COUNT(*) FROM stories s WHERE ${published("s")} AND ${storyOwner(excluded)}) AS public_stories,
            (SELECT COUNT(*) FROM stories s WHERE ${published("s")} AND s.user_id=:officialId) AS official_stories,
            (SELECT COUNT(*) FROM consumption x WHERE ${published("x")}) AS played_stories,
            (SELECT COALESCE(SUM(chats),0) FROM consumption) AS other_chats,
            (SELECT COALESCE(SUM(chats),0) FROM (SELECT chats FROM consumption ORDER BY chats DESC LIMIT 10) top_stories) AS top_chats
        """, params(from, until, excluded, officialId)) { rs, _ ->
            AdminStoryConsumptionCounts(rs.getLong("public_stories"), rs.getLong("official_stories"), rs.getLong("played_stories"),
                rs.getLong("other_chats"), rs.getLong("top_chats"))
        }!!

    fun ranking(from: Instant, until: Instant, excluded: List<Long>, officialId: Long?, limit: Int): List<AdminStoryConsumptionRank> =
        jdbc.query("""${consumption(excluded)} SELECT public_id,title,
            CASE WHEN user_id=:officialId THEN TRUE ELSE FALSE END AS official,deleted_at IS NOT NULL AS deleted,chats,players,turns
            FROM consumption ORDER BY chats DESC,turns DESC,CAST(public_id AS VARCHAR) ASC LIMIT :limit""",
            params(from, until, excluded, officialId) + mapOf("limit" to limit)) { rs, _ ->
            AdminStoryConsumptionRank(rs.getObject("public_id", UUID::class.java), rs.getString("title"), rs.getBoolean("official"),
                rs.getBoolean("deleted"), rs.getLong("chats"), rs.getLong("players"), rs.getLong("turns"))
        }

    fun creators(previousFrom: Instant, from: Instant, until: Instant, excluded: List<Long>): AdminStoryCreators =
        jdbc.queryForObject("""WITH creators AS (
            SELECT s.user_id,MAX(CASE WHEN s.created_at>=:from THEN 1 ELSE 0 END) AS current_creator,
                MAX(CASE WHEN s.created_at<:from THEN 1 ELSE 0 END) AS previous_creator
            FROM stories s WHERE s.created_at>=:previousFrom AND s.created_at<=:until
                AND s.user_id IS NOT NULL AND ${owner("s.user_id", excluded)} GROUP BY s.user_id
        ) SELECT
            (SELECT COUNT(DISTINCT c.user_id) FROM story_messages m JOIN story_chats c ON c.id=m.chat_id
                WHERE m.role='USER' AND m.created_at>=:from AND m.created_at<=:until
                    AND c.user_id IS NOT NULL AND ${owner("c.user_id", excluded)}) AS active_members,
            COALESCE(SUM(current_creator),0) AS members,COALESCE(SUM(previous_creator),0) AS previous_members,
            COALESCE(SUM(CASE WHEN current_creator=1 AND previous_creator=1 THEN 1 ELSE 0 END),0) AS retained
            FROM creators""", params(from, until, excluded, null) + mapOf("previousFrom" to previousFrom.atOffset(ZoneOffset.UTC))) { rs, _ ->
            val active = rs.getLong("active_members"); val members = rs.getLong("members")
            val previous = rs.getLong("previous_members"); val retained = rs.getLong("retained")
            AdminStoryCreators(active, members, if (active == 0L) null else members.toDouble() / active,
                previous, retained, if (previous == 0L) null else retained.toDouble() / previous)
        }!!
}
