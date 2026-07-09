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
 * 회원 엔딩 도달 집계(스펙 §4-3-10). (회원, 스토리, 엔딩) 단위로 최초 도달 1회를 upsert한다. 게스트는 집계하지 않는다.
 *
 * `GET /stories/{storyId}`의 reachedEndings 집계 소스이며, 게스트→회원 이관(/auth/migrate) 시 도달분을 백필한다.
 * FK는 user_id·story_id·ending_id 세 plain Long으로 매핑한다.
 */
@Entity
@Table(
    name = "user_story_ending_reaches",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_user_story_ending_reaches",
            columnNames = ["user_id", "story_id", "ending_id"],
        ),
    ],
)
class UserStoryEndingReach(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "ending_id", nullable = false)
    val endingId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
