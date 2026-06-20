package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryPlaySession
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryPlaySessionRepository : JpaRepository<StoryPlaySession, Long> {
    fun findByPublicId(publicId: UUID): StoryPlaySession?

    fun findAllByPublicIdIn(publicIds: Collection<UUID>): List<StoryPlaySession>
}
