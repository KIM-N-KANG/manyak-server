package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChatSummary
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface StoryChatSummaryRepository : JpaRepository<StoryChatSummary, Long> {
    fun findFirstByChatIdAndInvalidatedAtIsNullOrderByThroughTurnDesc(chatId: Long): StoryChatSummary?
    fun findFirstByChatIdAndInvalidatedAtIsNullAndThroughTurnLessThanOrderByThroughTurnDesc(chatId: Long, targetTurn: Int): StoryChatSummary?
    fun findByChatIdOrderByThroughTurnAsc(chatId: Long): List<StoryChatSummary>

    /** 호출자는 채팅 행 잠금을 보유해야 한다. 무효화 행은 대조를 위해 보존한다. */
    @Modifying
    @Query("UPDATE StoryChatSummary s SET s.invalidatedAt = :at WHERE s.chatId = :chatId AND s.invalidatedAt IS NULL AND s.throughTurn >= :targetTurn")
    fun invalidateFromTurn(chatId: Long, targetTurn: Int, at: Instant): Int
}
