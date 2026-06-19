package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChoice
import org.springframework.data.jpa.repository.JpaRepository

interface StoryChoiceRepository : JpaRepository<StoryChoice, Long> {
    fun findByMessageIdOrderByChoiceOrderAsc(messageId: Long): List<StoryChoice>

    fun findByMessageIdInOrderByChoiceOrderAsc(messageIds: Collection<Long>): List<StoryChoice>
}
