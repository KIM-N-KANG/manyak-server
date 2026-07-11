package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryLorebook
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

interface StoryLorebookRepository : JpaRepository<StoryLorebook, Long> {
    // lorebook을 함께 페치해 상세 조회 시 로어북별 추가 쿼리(N+1)를 피한다.
    @EntityGraph(attributePaths = ["lorebook"])
    fun findByStoryIdOrderBySortOrderAscIdAsc(storyId: Long): List<StoryLorebook>

    // 교체 저장(PUT)에서 기존 참조를 먼저 지운다. 반환값은 삭제한 행 수.
    @Transactional
    fun deleteByStoryId(storyId: Long): Long
}
