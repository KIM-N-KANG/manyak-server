package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationSession
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface StoryCreationSessionRepository : JpaRepository<StoryCreationSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StoryCreationSession s WHERE s.id = :id")
    fun findByIdForUpdate(id: Long): StoryCreationSession?
}
