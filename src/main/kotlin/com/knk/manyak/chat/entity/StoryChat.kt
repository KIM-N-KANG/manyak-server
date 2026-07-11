package com.knk.manyak.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class ChatStatus {
    ACTIVE,
    ENDED,
}

@Entity
@Table(name = "story_chats")
class StoryChat(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 외부에 노출하는 추측 불가능한 식별자. 순차 PK 열거(IDOR)를 막기 위해 API는 이 값만 입출력한다.
    // 내부 PK(id)는 FK·조인·성능용으로만 사용한다.
    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    val publicId: UUID = UUID.randomUUID(),

    // 게스트(비로그인) 생성 시 NULL. 로그인 후 마이그레이션(KNK-389)이 조건부 UPDATE로 요청자 user_id를 클레임한다.
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "start_setting_id")
    val startSettingId: Long? = null,

    @Column(length = 100)
    var title: String? = null,

    @Column(columnDefinition = "TEXT")
    var summary: String? = null,

    @Column(name = "current_turn", nullable = false)
    var currentTurn: Int = 0,

    // 재생성(§4-3-9)으로 마지막 턴을 교체한 완료 횟수. current_turn과 달리 재생성마다 증가한다.
    // 크레딧 선차감 대사(KNK-448)가 완료 수 = current_turn + regeneratedCount로 세어, 유료 재생성이 성공했는데도
    // 초과 환불되는 것을 막는다(재생성도 turn과 동일한 CHAT_TURN charge라 대사 버킷은 공유하되 완료 수만 보정).
    @Column(name = "regenerated_count", nullable = false)
    var regeneratedCount: Int = 0,

    // 목표 사건 런타임 상태(스펙 §4-3-10, D11). 판정은 AI가 하고 백엔드는 매 턴 요청에 되돌려 싣는다.
    // 현재 향해 진행 중인 주요 사건(story_main_events.id)과 그 진행 턴 수(채팅당 최대 1개).
    @Column(name = "target_main_event_id")
    var targetMainEventId: Long? = null,

    @Column(name = "target_progress_turns", nullable = false)
    var targetProgressTurns: Int = 0,

    // 최초 도달 엔딩(story_endings.id). 값이 있으면 이후 턴 요청에 엔딩 후보를 싣지 않아 채팅당 최초 1회를 보장한다.
    @Column(name = "reached_ending_id")
    var reachedEndingId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ChatStatus = ChatStatus.ACTIVE,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
