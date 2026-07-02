package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationStoryline
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationStorylineRepository : JpaRepository<StoryCreationStoryline, Long> {
    fun findByIdAndCreationSessionId(id: Long, creationSessionId: Long): StoryCreationStoryline?
}
