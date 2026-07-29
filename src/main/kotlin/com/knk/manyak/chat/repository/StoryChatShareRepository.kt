package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChatShare
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StoryChatShareRepository : JpaRepository<StoryChatShare, Long> {

    /** 멱등 재발급 판정(스펙 §4-3-11): 같은 채팅·커트라인 조합의 공유가 이미 있으면 그것을 그대로 쓴다. */
    fun findByChatIdAndTurnCutoff(chatId: Long, turnCutoff: Int): StoryChatShare?

    /** 공유 열람 토큰으로 조회한다. 없으면 null이고 호출부가 404로 통일한다(존재 여부 비노출). */
    fun findByPublicId(publicId: UUID): StoryChatShare?
}
