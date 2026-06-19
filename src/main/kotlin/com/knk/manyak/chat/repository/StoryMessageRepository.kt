package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface StoryMessageRepository : JpaRepository<StoryMessage, Long> {
    fun findByPlaySessionIdOrderByMessageOrderAsc(playSessionId: Long): List<StoryMessage>

    fun findByPlaySessionIdOrderByMessageOrderDesc(
        playSessionId: Long,
        pageable: Pageable,
    ): List<StoryMessage>

    fun findFirstByPlaySessionIdOrderByMessageOrderDesc(playSessionId: Long): StoryMessage?

    fun findByPlaySessionIdInAndRoleOrderByMessageOrderAsc(
        playSessionIds: Collection<Long>,
        role: MessageRole,
    ): List<StoryMessage>
}
