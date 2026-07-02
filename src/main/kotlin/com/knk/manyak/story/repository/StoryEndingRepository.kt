package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryEnding
import org.springframework.data.jpa.repository.JpaRepository

interface StoryEndingRepository : JpaRepository<StoryEnding, Long> {
    fun findByStoryIdOrderBySortOrderAsc(storyId: Long): List<StoryEnding>
}
