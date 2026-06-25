package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationExample
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationExampleRepository : JpaRepository<StoryCreationExample, Long> {
    fun findByIdAndCreationSessionId(id: Long, creationSessionId: Long): StoryCreationExample?
}
