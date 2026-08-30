package com.knk.manyak.story.entity

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

/** 신고 사유(스펙 §4-3-1 스토리 신고, KNK-1020). 세분화 수요가 생기면 값만 늘린다. */
enum class StoryReportReason {
    SPAM,
    INAPPROPRIATE,
    ETC,
}

/**
 * 스토리 신고(스펙 §4-3-1 스토리 신고, KNK-1020).
 *
 * `(user_id, story_id)` UNIQUE로 같은 회원의 같은 스토리 중복 신고를 DB가 멱등 흡수한다(좋아요와 동일 관례).
 * 접수 후 처리(노출 제재 등)는 도입 전이므로 이 테이블은 접수 원장 + 운영 Slack 알림의 앵커다.
 */
@Entity
@Table(
    name = "story_reports",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_reports_user_story",
            columnNames = ["user_id", "story_id"],
        ),
    ],
)
class StoryReport(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 신고한 회원(users.id). 게스트는 신고할 수 없어 nullable이 아니다.
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val reason: StoryReportReason,

    // 자유 서술(선택, 상한 500자 — 요청 검증). ETC 사유의 맥락 전달이 주 용도다.
    @Column(columnDefinition = "TEXT")
    val detail: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
