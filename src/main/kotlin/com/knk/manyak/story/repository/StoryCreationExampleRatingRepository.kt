package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationExampleRating
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationExampleRatingRepository : JpaRepository<StoryCreationExampleRating, Long> {
    fun findByExampleId(exampleId: Long): StoryCreationExampleRating?

    fun deleteByExampleId(exampleId: Long)
}
