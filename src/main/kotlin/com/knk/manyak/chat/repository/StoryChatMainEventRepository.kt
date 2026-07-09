package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryChatMainEvent
import org.springframework.data.jpa.repository.JpaRepository

interface StoryChatMainEventRepository : JpaRepository<StoryChatMainEvent, Long> {

    // 채팅이 거쳐온 사건 목록(occurred_main_event_names 재료). 이름은 story_main_events에서 id로 해소한다.
    fun findByChatId(chatId: Long): List<StoryChatMainEvent>

    // 최초 1회 upsert 가드. 이미 완결된 사건이면 다시 저장하지 않는다(유니크 (chat_id, main_event_id)).
    fun existsByChatIdAndMainEventId(chatId: Long, mainEventId: Long): Boolean
}
