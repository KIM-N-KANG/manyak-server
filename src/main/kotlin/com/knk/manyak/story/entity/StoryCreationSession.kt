package com.knk.manyak.story.entity

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

enum class StoryCreationSessionStatus {
    STORYLINES_GENERATED,
    STORY_CREATED,
}

@Entity
@Table(name = "story_creation_sessions")
class StoryCreationSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 익명으로 시작한 세션을 로그인 사용자가 완료(claim)하면 소유자를 박을 수 있어야 한다(KNK-286).
    @Column(name = "user_id")
    var userId: Long? = null,

    @Column(name = "story_id")
    var storyId: Long? = null,

    // 완성(STORY_CREATED) 시점의 생성 요청 request_id(KNK-644). 회수 재실행이 "이 세션을 만든 그 요청"인지 검증해,
    // 소유자 없는 게스트 세션도 안전하게 스토리를 재구성 복구한다(KNK-635 Codex P1의 바인딩 부재 해소).
    @Column(name = "creation_request_id")
    var creationRequestId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: StoryCreationSessionStatus,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun updateTimestamp() {
        updatedAt = Instant.now()
    }
}
