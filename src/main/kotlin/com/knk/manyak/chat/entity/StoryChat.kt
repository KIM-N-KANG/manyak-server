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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
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

    /**
     * **다음 릴리스의 DROP 대상**(KNK-1065). 읽기 정본은 `stories.last_public_snapshot`으로 옮겼고 이 **둘은**
     * 아무도 읽지 않는다. 그런데도 채팅 생성 시 계속 채우고 컬럼을 남겨 둔다 — ECS 롤링 배포는 새 태스크가
     * Flyway를 돌리는 동안 구버전 태스크가 계속 요청을 받고, 배포를 되돌리면 구버전이 그대로 뜬다.
     * 구버전 엔티티에 이 컬럼들이 매핑돼 있어서 지금 지우면 그 창 동안 서재·상세·공유·이용내역의 SELECT가
     * 통째로 실패한다(컬럼 추가와 달리 DROP은 하위 호환이 아니다). 릴리스가 끝난 뒤 별도 티켓에서 지운다.
     */
    @Column(name = "story_title_snapshot", length = 100)
    val storyTitleSnapshot: String? = null,

    @Column(name = "story_thumbnail_key_snapshot", length = 64)
    val storyThumbnailKeySnapshot: String? = null,

    /**
     * 채팅 시작 시점의 프롤로그. 위 둘과 달리 **DROP 대상이 아니고 지금도 읽는다**(PR #224 Codex P2).
     *
     * 스토리 스냅샷은 시작 설정을 id로 찾는데, 소유자가 편집 폼에서 시작 설정 항목을 빼면 행이 삭제되고
     * FK(`ON DELETE SET NULL`)가 [startSettingId]를 비운다. 조회 키가 사라져 사전으로는 덮을 수 없다 —
     * [reachedEndingNameSnapshot]과 같은 구조의 파괴라 같은 방식으로 폴백한다
     * ([ChatService]의 `brokenReferencePrologue`). 이름·시작 상황은 여기 없어 부분 복구다.
     */
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

    /**
     * 이 채팅이 완결한 주요 사건의 **이름** 스냅샷(PR #224 Codex P2). [reachedEndingNameSnapshot]과 같은
     * 이유로 남기며 DROP 대상이 아니다.
     *
     * 완결 기록의 정본은 `story_chat_main_events`인데, 그 테이블의 `main_event_id`가 `story_main_events`
     * FK **ON DELETE CASCADE**라 수정 API의 `mainEvents[]` 전체 교체가 행을 지우는 순간 남의 채팅 완결
     * 기록이 통째로 사라진다. 그러면 AI 턴 요청은 스냅샷의 옛 사건을 후보로 보내면서 "이미 완결했다"는
     * 사실은 못 보내, 독자가 **이미 지난 사건을 다시 겪는다**.
     *
     * 목록이라 jsonb 배열이다. `story_chats`는 서재에서 100건까지 eager로 뜨지만 짧은 이름 몇 개라,
     * 같은 행에 이미 있는 [storyPrologueSnapshot](TEXT 본문) 옆에서 무시할 만한 크기다
     * (수십 KB까지 커질 수 있는 스토리 스냅샷을 별도 테이블로 뺀 것과는 사정이 다르다).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "occurred_main_event_names_snapshot")
    var occurredMainEventNamesSnapshot: List<String>? = null,

    // 최초 도달 엔딩(story_endings.id). 값이 있으면 이후 턴 요청에 엔딩 후보를 싣지 않아 채팅당 최초 1회를 보장한다.
    @Column(name = "reached_ending_id")
    var reachedEndingId: Long? = null,

    /**
     * 도달 시점의 엔딩 이름(KNK-1059). 위 세 컬럼과 달리 **DROP 대상이 아니고 지금도 읽는다**.
     *
     * 스토리 스냅샷은 "엔딩 id → 이름" 사전인데, 수정 API의 `endings[]` 전체 교체가 행을 삭제·재생성하면
     * FK(`ON DELETE SET NULL`, V41)가 [reachedEndingId]와 `story_messages.reached_ending_id`를 **동시에**
     * 비운다. 사전을 조회할 키가 사라지므로 사전으로는 덮을 수 없다. 비공개 스토리만의 문제가 아니다 —
     * 공개 스토리에서 제작자가 엔딩을 손보기만 해도 그 스토리로 놀던 모든 독자의 도달 기록이 날아간다.
     *
     * [reachedEndingId]를 박는 그 자리에서 함께 기록한다([ChatTurnPersister]). 도달은 채팅당 최초 1회뿐이라
     * 컬럼 하나면 된다. 원본과 타입을 맞춘다(story_endings.name varchar(100)).
     */
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
