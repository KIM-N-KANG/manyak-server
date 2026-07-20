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

    // 소유 주체: 회원 요청은 user_id, 게스트 요청은 device_id(원문). 복구 조회 소유 게이트에 쓴다.
    @Column(name = "user_id")
    val userId: Long? = null,

    @Column(name = "device_id")
    val deviceId: String? = null,

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
 * 복구 조회·재요청 소유 게이트. 회원 소유 행은 요청 userId가 일치해야 하고, 게스트 소유 행은
 * 요청 deviceId가 (둘 다 non-null로) 일치해야 한다. 소유자가 식별되지 않는 행(둘 다 null)은 누구에게도 열리지 않는다.
 */
fun StoryCreationRequest.isOwnedBy(userId: Long?, deviceId: String?): Boolean =
    if (this.userId != null) this.userId == userId
    else this.deviceId != null && this.deviceId == deviceId
