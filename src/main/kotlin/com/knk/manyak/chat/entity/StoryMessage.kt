package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

@Entity
@Table(
    name = "story_messages",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_messages_order",
            columnNames = ["chat_id", "message_order"],
        ),
    ],
)
class StoryMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "chat_id", nullable = false)
    val chatId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val role: MessageRole,

    // 재생성(§4-3-9)은 마지막 ASSISTANT 활성본을 같은 사용자 입력으로 다시 생성해 제자리 교체한다(var로 교체 허용).
    // 덮어쓰기 직전 이전 본문·선택지는 [StoryMessageVersion] 이력으로 보관한다(B11). USER 입력은 교체되지 않는다.
    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "message_order", nullable = false)
    val messageOrder: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
