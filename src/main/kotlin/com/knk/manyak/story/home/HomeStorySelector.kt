package com.knk.manyak.story.home

import com.knk.manyak.story.service.StoryListSort
import com.knk.manyak.story.service.StoryListSort.Companion.POPULAR_LIKE_WEIGHT
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** 공개 목록과 같은 집계, 인기 가중치와 public_id 동률 순서를 사용한다. 섹션당 한 번 선정한다. */
@Repository
class HomeStorySelector(private val jdbc: NamedParameterJdbcTemplate) {
    fun select(sort: StoryListSort, genres: Collection<String>, originalOnly: Boolean, officialId: Long?, limit: Int): List<Long> {
        if (originalOnly && officialId == null) return emptyList()
        val turns = "(SELECT COALESCE(SUM(c.current_turn + c.regenerated_count), 0) FROM story_chats c WHERE c.story_id = s.id)"
        val likes = "(SELECT COUNT(*) FROM story_likes l WHERE l.story_id = s.id)"
        val order = when (sort) {
            StoryListSort.POPULAR -> "($likes * $POPULAR_LIKE_WEIGHT + $turns)"
            StoryListSort.LATEST -> "s.created_at"
            StoryListSort.LIKES -> likes
            StoryListSort.CHATS -> turns
        }
        val params = mutableMapOf<String, Any>("limit" to limit)
        val where = StringBuilder("s.status = 'PUBLISHED' AND s.visibility = 'PUBLIC' AND s.deleted_at IS NULL")
        if (originalOnly) { where.append(" AND s.user_id = :owner"); params["owner"] = officialId!! }
        if (genres.isNotEmpty()) {
            val conditions = genres.mapIndexed { index, genre ->
                val escaped = genre.lowercase().replace(" ", "").replace("!", "!!").replace("%", "!%").replace("_", "!_")
                params["genre$index"] = "%,$escaped,%"
                "CONCAT(',', REPLACE(LOWER(COALESCE(s.genre, '')), ' ', ''), ',') LIKE :genre$index ESCAPE '!'"
            }
            where.append(" AND (").append(conditions.joinToString(" OR ")).append(')')
        }
        return jdbc.query("SELECT s.id FROM stories s WHERE $where ORDER BY $order DESC, s.public_id DESC LIMIT :limit", params) { rs, _ -> rs.getLong("id") }
    }
}
