package com.knk.manyak.push.entity

import com.knk.manyak.push.dto.PushKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 캠페인 한 건의 생애(KNK-1117). 상태 전이는 `SCHEDULED → SENDING → SENT|FAILED`이고 `CANCELED`는 집기 전에만 들어간다. */
enum class PushCampaignStatus {
    SCHEDULED,
    SENDING,
    SENT,
    CANCELED,
    FAILED,
}

/**
 * 프로모션 푸시의 예약과 이력(KNK-1117, V77, 스펙 §4-3-5).
 *
 * 관리자가 API로 `SCHEDULED` 행을 만들면 예약이고, 스케줄러가 도래한 행을 조건부 UPDATE로 집어 발송한다.
 * 원격 모드는 회원별 아웃박스 메시지 식별자로 재실행 중복을 막는다.
 *
 * 카운트와 시각이 `var`인 이유: 회차가 끝날 때 같은 행에 결과를 적는다. 문구·예약 시각은 운영자 소유라 `val`이다.
 */
@Entity
@Table(
    name = "push_campaigns",
    indexes = [
        Index(name = "idx_push_campaigns_status_scheduled_at", columnList = "status, scheduled_at"),
    ],
)
class PushCampaign(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** 푸시 페이로드의 `campaignId`. 순차 PK를 노출하지 않는다. */
    @Column(name = "public_id", nullable = false, unique = true)
    val publicId: UUID = UUID.randomUUID(),

    /** `(광고)` 접두 없는 원문. 접두는 발송 시점에 서버가 붙인다. */
    @Column(nullable = false, length = 100)
    val title: String,

    @Column(nullable = false, length = 300)
    val body: String,

    @Column(name = "scheduled_at", nullable = false)
    val scheduledAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PushCampaignStatus = PushCampaignStatus.SCHEDULED,

    @Column(name = "target_count")
    var targetCount: Int? = null,

    @Column(name = "sent_count")
    var sentCount: Int? = null,

    /** 구버전 호환을 위해 보존하며 신규 회차에서는 읽거나 쓰지 않는다. */
    @Column(name = "skipped_count", insertable = false, updatable = false)
    var skippedCount: Int? = null,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "deep_link_included")
    var deepLinkIncluded: Boolean? = null,

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: PushKind = PushKind.MARKETING,

    @Column(name = "notice_id", unique = true)
    val noticeId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "notice_tag", length = 16)
    val noticeTag: com.knk.manyak.notice.entity.NoticeTag? = null,

    /** 이동 대상 스토리의 내부 FK. API와 푸시 데이터에는 스토리 publicId를 사용한다. */
    @Column(name = "story_id")
    val storyId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
