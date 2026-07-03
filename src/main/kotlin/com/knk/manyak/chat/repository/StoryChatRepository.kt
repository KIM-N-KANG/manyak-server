package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChat
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryChatRepository : JpaRepository<StoryChat, Long> {
    // 소프트 삭제된(deleted_at IS NOT NULL) 채팅은 조회에서 제외한다.
    fun findByPublicIdAndDeletedAtIsNull(publicId: UUID): StoryChat?

    fun findAllByPublicIdInAndDeletedAtIsNull(publicIds: Collection<UUID>): List<StoryChat>
}
