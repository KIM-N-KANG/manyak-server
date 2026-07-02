package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryLorebook
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface StoryLorebookRepository : JpaRepository<StoryLorebook, Long> {
    // lorebook을 함께 페치해 상세 조회 시 로어북별 추가 쿼리(N+1)를 피한다.
    @EntityGraph(attributePaths = ["lorebook"])
    fun findByStoryIdOrderBySortOrderAscIdAsc(storyId: Long): List<StoryLorebook>
}
