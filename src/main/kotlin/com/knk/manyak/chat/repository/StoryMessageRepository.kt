package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StoryMessageRepository : JpaRepository<StoryMessage, Long> {
    fun findByPlaySessionIdOrderByMessageOrderAsc(playSessionId: Long): List<StoryMessage>

    fun findByPlaySessionIdOrderByMessageOrderDesc(
        playSessionId: Long,
        pageable: Pageable,
    ): List<StoryMessage>

    fun findFirstByPlaySessionIdOrderByMessageOrderDesc(playSessionId: Long): StoryMessage?

    /**
     * 주어진 세션들에서 해당 role의 마지막(messageOrder 최대) 메시지만 세션당 1건씩 조회한다.
     * 목록 프리뷰처럼 세션별 최신 한 건만 필요할 때 메모리 로드를 줄이기 위해 사용한다.
     */
    @Query(
        """
        SELECT sm FROM StoryMessage sm
        WHERE sm.playSessionId IN :playSessionIds
          AND sm.role = :role
          AND sm.messageOrder = (
              SELECT MAX(sub.messageOrder)
              FROM StoryMessage sub
              WHERE sub.playSessionId = sm.playSessionId
                AND sub.role = :role
          )
        """,
    )
    fun findLatestMessagesByPlaySessionIdsAndRole(
        @Param("playSessionIds") playSessionIds: Collection<Long>,
        @Param("role") role: MessageRole,
    ): List<StoryMessage>
}
