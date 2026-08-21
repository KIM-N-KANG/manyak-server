package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationCharacter
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationCharacterRepository : JpaRepository<StoryCreationCharacter, Long> {
    fun findAllByCreationSessionIdOrderByRoleAscSortOrderAscIdAsc(creationSessionId: Long): List<StoryCreationCharacter>
}
