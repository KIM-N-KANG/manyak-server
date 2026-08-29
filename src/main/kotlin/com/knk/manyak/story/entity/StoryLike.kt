package com.knk.manyak.story.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 스토리 좋아요(스펙 §4-3-1 스토리 좋아요, KNK-1017).
 *
 * like만 있고 dislike는 없으므로 값 컬럼 없이 **행 존재 자체가 좋아요**다. `(user_id, story_id)` UNIQUE가
 * 등록 멱등을 DB에서 보장하고, 이 테이블이 `likeCount` 실 집계와 상세 `isLiked` 판정의 앵커다.
 * 탐색이 필요 없어 연관 매핑 없이 원시 FK 값만 보관한다(평가 엔티티와 같은 관례).
 */
@Entity
@Table(
    name = "story_likes",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_story_likes_user_story",
            columnNames = ["user_id", "story_id"],
        ),
    ],
)
class StoryLike(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    // 좋아요한 회원(users.id). 게스트는 좋아요할 수 없어 nullable이 아니다.
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
