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

    // 이 ASSISTANT 메시지가 엔딩 응답이면 도달한 엔딩(story_endings.id). 도달 턴에만 채워지고 이후 재생성은 409로 막힌다.
    @Column(name = "reached_ending_id")
    val reachedEndingId: Long? = null,

    /**
     * 도달 시점의 엔딩 이름(PR #224 Codex P2 재리뷰). [reachedEndingId]가 `story_endings` FK
     * `ON DELETE SET NULL`이라 소유자가 엔딩을 교체하면 비워지는데, 이 컬럼에는 FK가 없어 남는다.
     *
     * **이 컬럼이 곧 "이 턴이 도달 턴이었다"는 표식**이다. 그래서 상세·공유의 턴 단위 도달 표시가
     * id 없이도 성립한다 — id만으로는 어느 턴이 도달 턴이었는지 알 수 없어 복구를 포기했던 자리다.
     *
     * 도달 판정이 라이브 엔딩 행을 못 찾아 id 없이 기록되는 경우([ChatTurnPersister.resolveReachedEnding])
     * 에도 이름은 채운다.
     */
    @Column(name = "reached_ending_name_snapshot", length = 100)
    val reachedEndingNameSnapshot: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
