package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryLorebook
import org.springframework.data.jpa.repository.JpaRepository

interface StoryLorebookRepository : JpaRepository<StoryLorebook, Long> {
    fun findByStoryIdOrderBySortOrderAscIdAsc(storyId: Long): List<StoryLorebook>
}
