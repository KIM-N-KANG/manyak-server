package com.knk.manyak.story.home

import com.knk.manyak.story.service.StoryListSort
import com.knk.manyak.story.repository.StoryListExpressions.LIKE_COUNT
import com.knk.manyak.story.repository.StoryListExpressions.POPULAR_SCORE
import com.knk.manyak.story.repository.StoryListExpressions.TURN_COUNT
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StoryVisibility
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

/** 공개 목록과 같은 집계, 인기 가중치와 public_id 동률 순서를 사용한다. 섹션당 한 번 선정한다. */
@Repository
class HomeStorySelector(private val entityManager: EntityManager) {
    fun select(sort: StoryListSort, genres: Collection<String>, originalOnly: Boolean, officialId: Long?, limit: Int): List<Long> {
        if (originalOnly && officialId == null) return emptyList()
        val order = when (sort) {
            StoryListSort.POPULAR -> POPULAR_SCORE
            StoryListSort.LATEST -> "s.createdAt"
            StoryListSort.LIKES -> LIKE_COUNT
            StoryListSort.CHATS -> TURN_COUNT
        }
        val params = mutableMapOf<String, Any>("status" to StoryStatus.PUBLISHED, "visibility" to StoryVisibility.PUBLIC)
        val where = StringBuilder("s.status = :status AND s.visibility = :visibility AND s.deletedAt IS NULL")
        if (originalOnly) { where.append(" AND s.userId = :owner"); params["owner"] = officialId!! }
        if (genres.isNotEmpty()) {
            val conditions = genres.mapIndexed { index, genre ->
                val escaped = genre.lowercase().replace(" ", "").replace("!", "!!").replace("%", "!%").replace("_", "!_")
                params["genre$index"] = "%,$escaped,%"
                "CONCAT(',', REPLACE(LOWER(COALESCE(s.genre, '')), ' ', ''), ',') LIKE :genre$index ESCAPE '!'"
            }
            where.append(" AND (").append(conditions.joinToString(" OR ")).append(')')
        }
        val query = entityManager.createQuery("SELECT s.id FROM Story s WHERE $where ORDER BY $order DESC, s.publicId DESC", Long::class.javaObjectType)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        return query.setMaxResults(limit).resultList
    }
}
