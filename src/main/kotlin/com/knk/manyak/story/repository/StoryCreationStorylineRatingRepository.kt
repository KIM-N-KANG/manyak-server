package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationStorylineRating
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationStorylineRatingRepository : JpaRepository<StoryCreationStorylineRating, Long> {
    fun findByStorylineId(storylineId: Long): StoryCreationStorylineRating?

    fun deleteByStorylineId(storylineId: Long)
}
