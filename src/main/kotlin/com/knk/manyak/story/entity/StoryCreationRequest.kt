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

/** 백그라운드 생성 복구 대상 단계(스펙 §4-3-8): 스토리라인 생성 또는 스토리 완성. */
enum class StoryCreationStage {
    STORYLINE_GENERATION,
    STORY_COMPLETION,
}

/** 생성 요청 진행 상태. 요청 수신 시 PENDING으로 기록하고, 성공 COMPLETED·실패 FAILED로 갱신한다. */
enum class StoryCreationRequestStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

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
 * 복구 조회·재요청 소유 게이트. 요청의 식별자(회원 userId·디바이스 해시) 중 **하나라도** 행의 값과 일치하면 소유로 본다.
 * 인증 상태가 요청 사이에 바뀌어도(만료 토큰↔갱신 토큰) 같은 클라이언트의 멱등 재시도가 소유 불일치로 막히지 않게 하기 위함이다(Codex P2).
 * 두 식별자가 모두 없는(회원도 아니고 디바이스도 없는) 행은 누구에게도 열리지 않는다.
 */
fun StoryCreationRequest.isOwnedBy(userId: Long?, deviceIdHash: String?): Boolean =
    (this.userId != null && this.userId == userId) ||
        (this.deviceIdHash != null && this.deviceIdHash == deviceIdHash)
