package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 채팅이 거쳐온(완결한) 주요 사건 기록(스펙 §4-3-10, D11). 한 채팅에서 같은 사건은 한 번만 완결된다.
 *
 * AI가 completed 메타로 반환한 완결 사건을 백엔드가 이 표에 upsert하고, 다음 턴 요청의
 * occurred_main_event_names로 되돌려 싣는다. FK는 chat_id·main_event_id 두 plain Long으로 매핑한다.
 */
@Entity
@Table(
    name = "story_chat_main_events",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_chat_main_events",
            columnNames = ["chat_id", "main_event_id"],
        ),
    ],
)
class StoryChatMainEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Column(name = "main_event_id", nullable = false)
    val mainEventId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
