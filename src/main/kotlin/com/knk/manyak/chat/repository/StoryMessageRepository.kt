package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.MessageRole
import com.knk.manyak.chat.entity.StoryMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StoryMessageRepository : JpaRepository<StoryMessage, Long> {
    fun findByChatIdOrderByMessageOrderAsc(chatId: Long): List<StoryMessage>

    /**
     * message_order 상한 이하의 메시지만 순서대로 조회한다. 채팅 공유 열람(§4-3-11)이 커트라인까지만 읽어
     * 전체 이력 로드를 피하는 데 쓴다. 상한은 [findByChatIdAndRoleOrderByMessageOrderAsc]로 구한 실제 경계 값이며,
     * order 산술(턴 N = 2N)로 계산하지 않는다 — SYSTEM 메시지가 앞에 끼면 그 가정이 깨진다.
     */
    fun findByChatIdAndMessageOrderLessThanEqualOrderByMessageOrderAsc(
        chatId: Long,
        messageOrder: Int,
    ): List<StoryMessage>

    /**
     * 채팅에서 해당 role의 메시지를 order 오름차순으로 조회한다(페이지로 잘라 사용).
     * 공유 열람이 `PageRequest.of(N - 1, 1)`로 **N번째 ASSISTANT**만 집어 커트라인 경계를 논리 턴 기준으로 구한다.
     */
    fun findByChatIdAndRoleOrderByMessageOrderAsc(
        chatId: Long,
        role: MessageRole,
        pageable: Pageable,
    ): List<StoryMessage>

    fun findByChatIdOrderByMessageOrderDesc(
        chatId: Long,
        pageable: Pageable,
    ): List<StoryMessage>

    fun findFirstByChatIdOrderByMessageOrderDesc(chatId: Long): StoryMessage?

    /**
     * 주어진 채팅들에서 해당 role의 마지막(messageOrder 최대) 메시지만 채팅당 1건씩 조회한다.
     * 목록 프리뷰처럼 채팅별 최신 한 건만 필요할 때 메모리 로드를 줄이기 위해 사용한다.
     */
    @Query(
        """
        SELECT sm FROM StoryMessage sm
        WHERE sm.chatId IN :chatIds
          AND sm.role = :role
          AND sm.messageOrder = (
              SELECT MAX(sub.messageOrder)
              FROM StoryMessage sub
              WHERE sub.chatId = sm.chatId
                AND sub.role = :role
          )
        """,
    )
    fun findLatestMessagesByChatIdsAndRole(
        @Param("chatIds") chatIds: Collection<Long>,
        @Param("role") role: MessageRole,
    ): List<StoryMessage>
}
