package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationStoryline
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationStorylineRepository : JpaRepository<StoryCreationStoryline, Long> {
    fun findByIdAndCreationSessionId(id: Long, creationSessionId: Long): StoryCreationStoryline?

    /** 세션의 스토리라인을 저장 순서(storyline_order 오름차순)로 조회한다(KNK-848 회수 재구성). */
    fun findByCreationSessionIdOrderByStorylineOrderAscIdAsc(creationSessionId: Long): List<StoryCreationStoryline>
}
