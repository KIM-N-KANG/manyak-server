package com.knk.manyak.story.entity

import com.knk.manyak.story.dto.ParentCreationLink
import com.knk.manyak.story.dto.ParentLinkError
import com.knk.manyak.story.dto.StoryCreationRequestStatus
import com.knk.manyak.story.dto.StoryCreationStage
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

/**
 * 백그라운드 생성 복구·멱등 추적(KNK-631, 스펙 §4-3-8). 클라이언트 생성 [requestId]로 생성 요청을 식별해,
 * 앱 전환으로 응답을 못 받아도 복구 조회로 결과를 되찾고, 같은 requestId 재요청의 중복 생성을 막는다.
 */
@Entity
@Table(name = "story_creation_requests")
class StoryCreationRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "request_id", nullable = false, unique = true)
    val requestId: UUID,

    // 소유 주체: 회원 요청은 user_id, 게스트 요청은 device_id_hash(원문 대신 해시 — DeviceIdHasher). 복구 조회 소유 게이트에 쓴다.
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(name = "device_id_hash", length = 64)
    val deviceIdHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 32)
    val stage: StoryCreationStage,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: StoryCreationRequestStatus = StoryCreationRequestStatus.PENDING,

    // 성공 시 원 POST 응답 본문(JSON 직렬화). 복구 조회·멱등 replay에 그대로 반환한다.
    @Column(name = "result_json", columnDefinition = "TEXT")
    var resultJson: String? = null,

    // 재생성 체인(KNK-755). 셋 다 행 삽입 시점에 확정되며 이후 갱신하지 않는다.
    // 검증을 통과한 부모의 request_id(자기참조). 검증 실패·부모 미지정이면 null이다.
    @Column(name = "parent_request_id")
    val parentRequestId: UUID? = null,

    // 프론트가 실제로 보낸 원값. 검증 통과 여부와 무관하게 그대로 남긴다(안 보냈으면 null).
    @Column(name = "attempted_parent_creation_id")
    val attemptedParentCreationId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "parent_link_error", length = 32)
    val parentLinkError: ParentLinkError? = null,

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

/**
 * 복구 조회·재요청 소유 게이트.
 * - **회원 소유 행**(userId 있음)은 요청 userId가 일치해야 한다. 같은 디바이스라도 익명·다른 계정은 소유가 아니다
 *   (공유 기기·계정 전환에서 이전 회원의 생성 결과가 노출·replay되는 것을 막는다 — Codex P2).
 * - **게스트 소유 행**(userId 없음)은 요청 디바이스 해시가 (둘 다 non-null로) 일치해야 한다. 만료 토큰으로 게스트로 기록된 뒤
 *   토큰을 갱신한 같은 디바이스의 회원 재시도가 이 경로로 매칭돼 멱등이 유지된다(요청 측 디바이스 해시는 회원이어도 버리지 않는다).
 * - 두 식별자가 모두 없는 행은 누구에게도 열리지 않는다.
 */
fun StoryCreationRequest.isOwnedBy(userId: Long?, deviceIdHash: String?): Boolean =
    if (this.userId != null) this.userId == userId
    else this.deviceIdHash != null && this.deviceIdHash == deviceIdHash

/**
 * 이미 기록된 행이 들고 있는 체인을 그대로 되살린다(KNK-755).
 *
 * 재실행(FAILED 재시도·aged PENDING 회수)은 요청 행을 새로 넣지 않으므로 체인은 **최초 삽입 때 확정된 값**이 정본이다.
 * 그런데 재시도 본문의 parentCreationId가 빠지거나 바뀌었을 수 있어, 그 본문으로 다시 만든 링크를 AI 헤더에 실으면
 * 헤더와 `parent_request_id`가 어긋난다 — 정본 컬럼과 다른 체인이 trace로 나가는 셈이다. 그래서 재실행은 이 함수로
 * 저장된 값을 복원해 쓴다. 부모를 애초에 안 보낸 행은 null이다.
 */
fun StoryCreationRequest.parentCreationLink(): ParentCreationLink? =
    attemptedParentCreationId?.let { ParentCreationLink(it, parentLinkError) }

/**
 * 재생성 체인 소유 연속성 게이트(KNK-755). 복구 조회 게이트인 [isOwnedBy]와 **규칙이 달라** 별도로 둔다.
 *
 * - **양쪽 다 회원이면** `userId` 엄격 일치만 인정하고 디바이스 해시로 보조 판정하지 않는다. 같은 기기에서 계정 A가
 *   로그아웃하고 계정 B가 재생성하면 디바이스 해시는 같아도 서로 다른 계정의 여정이라, 기기 일치만으로 이으면 여정이 섞인다.
 * - **한쪽 이상이 게스트**(userId 없음)면 디바이스 해시 일치로 판정한다. 회원 요청에도 디바이스 해시가 저장되므로
 *   게스트→회원 전환 중(같은 기기)에도 체인이 이어진다. 둘 다 non-null일 때만 일치로 본다(null == null은 일치가 아니다).
 */
fun StoryCreationRequest.hasSameChainOwnerAs(userId: Long?, deviceIdHash: String?): Boolean =
    if (this.userId != null && userId != null) this.userId == userId
    else this.deviceIdHash != null && this.deviceIdHash == deviceIdHash
