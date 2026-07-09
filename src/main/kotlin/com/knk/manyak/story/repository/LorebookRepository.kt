package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Lorebook
import org.springframework.data.jpa.repository.JpaRepository

interface LorebookRepository : JpaRepository<Lorebook, Long> {
    fun findByIsActiveTrueOrderByGenreAscSortOrderAscIdAsc(): List<Lorebook>

    fun findByGenreAndIsActiveTrueOrderBySortOrderAscIdAsc(genre: String): List<Lorebook>

    // 간편 제작 컴파일: 스토리의 장르 태그와 일치하는 활성 로어북을 선별한다(스펙 §4-3-6 런타임 반영).
    fun findByGenreInAndIsActiveTrueOrderByGenreAscSortOrderAscIdAsc(genres: Collection<String>): List<Lorebook>

    fun findByIdInAndIsActiveTrue(ids: Collection<Long>): List<Lorebook>
}
