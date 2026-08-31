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

    // 이 채팅이 시작한 스토리의 간편 제작 creation_id(KNK-751). 채팅 생성 시 1회 해석해 박고,
    // 이후 턴마다 조회 없이 그대로 AI 호출 헤더에 싣는다. 일반 제작(저작) 스토리는 null이라 헤더를 생략한다.
    @Column(name = "creation_id", updatable = false)
    val creationId: UUID? = null,

    // 채팅 시작 시점의 스토리 제목·썸네일 키(KNK-1059). creationId와 같은 성격의 1회 해석 값이다 —
    // 소유자가 스토리를 비공개로 되돌리거나 지운 뒤에도 서재·이용내역이 보여줄 값이 있어야 하기 때문에 박아둔다.
    // 원본 컬럼과 타입을 맞춘다(stories.title varchar(100), stories.thumbnail_image_key varchar(64)).
    @Column(name = "story_title_snapshot", length = 100)
    val storyTitleSnapshot: String? = null,

    @Column(name = "story_thumbnail_key_snapshot", length = 64)
    val storyThumbnailKeySnapshot: String? = null,

    // 시작 설정의 프롤로그 본문도 같이 박는다. 제목보다 유출 폭이 커서다 — 공유 열람은 무인증 경로라
    // 비공개로 되돌린 스토리의 도입부 본문이 링크만 가진 누구에게나 흘러갔다.
    // 원본과 타입을 맞춘다(story_start_settings.prologue TEXT, nullable).
    @Column(name = "story_prologue_snapshot", columnDefinition = "TEXT")
    val storyPrologueSnapshot: String? = null,

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

    // 도달 시점의 엔딩 이름 스냅샷(KNK-1059). 제목·프롤로그와 달리 채팅 생성 시점에는 정해지지 않아
    // [reachedEndingId]를 박는 그 자리에서 함께 기록한다. 채팅당 도달 엔딩은 최초 1회뿐이라 컬럼 하나로
    // 서재·상세·공유 세 경로를 모두 덮는다. 원본과 타입을 맞춘다(story_endings.name varchar(100)).
    @Column(name = "reached_ending_name_snapshot", length = 100)
    var reachedEndingNameSnapshot: String? = null,

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
