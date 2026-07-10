package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCharacter
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCharacterRepository : JpaRepository<StoryCharacter, Long> {
    fun findByStoryIdOrderByIdAsc(storyId: Long): List<StoryCharacter>
}
