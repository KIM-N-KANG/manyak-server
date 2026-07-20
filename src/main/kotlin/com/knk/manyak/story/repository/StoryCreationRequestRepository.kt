package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationRequest
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryCreationRequestRepository : JpaRepository<StoryCreationRequest, Long> {
    fun findByRequestId(requestId: UUID): StoryCreationRequest?
}
