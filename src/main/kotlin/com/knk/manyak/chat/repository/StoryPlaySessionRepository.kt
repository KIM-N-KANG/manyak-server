package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryPlaySession
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryPlaySessionRepository : JpaRepository<StoryPlaySession, Long> {
    // 소프트 삭제된(deleted_at IS NOT NULL) 세션은 조회에서 제외한다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): StoryPlaySession?

    fun findAllByPublicIdInAndDeletedAtIsNull(publicIds: Collection<UUID>): List<StoryPlaySession>
}
