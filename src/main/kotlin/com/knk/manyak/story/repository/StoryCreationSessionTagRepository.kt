package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationSessionTag
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface StoryCreationSessionTagRepository : JpaRepository<StoryCreationSessionTag, Long> {
    @Query(
        "SELECT st FROM StoryCreationSessionTag st JOIN FETCH st.tag WHERE st.creationSession.id = :sessionId",
    )
    fun findAllWithTagByCreationSessionId(sessionId: Long): List<StoryCreationSessionTag>
}
