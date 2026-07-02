package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Lorebook
import org.springframework.data.jpa.repository.JpaRepository

interface LorebookRepository : JpaRepository<Lorebook, Long> {
    fun findByIsActiveTrueOrderByGenreAscSortOrderAscIdAsc(): List<Lorebook>

    fun findByGenreAndIsActiveTrueOrderBySortOrderAscIdAsc(genre: String): List<Lorebook>

    fun findByIdInAndIsActiveTrue(ids: Collection<Long>): List<Lorebook>
}
