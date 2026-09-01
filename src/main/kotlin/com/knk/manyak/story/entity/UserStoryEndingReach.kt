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
 * 회원 엔딩 도달 집계(스펙 §4-3-10). (회원, 스토리, **엔딩 이름**) 단위로 최초 도달 1회를 upsert한다(앱 판정). 게스트는 집계하지 않는다.
 *
 * `GET /stories/{storyId}`의 reachedEndings 집계 소스이며, 게스트→회원 이관(/auth/migrate) 시 도달분을 백필한다.
 *
 * **정본 식별자는 [endingNameSnapshot]이고 [endingId]는 보조 참조다**(V70·V71, PR #224 Codex P2). 원래 ending_id가
 * NOT NULL이고 FK가 ON DELETE CASCADE였는데, 스토리 수정의 `endings[]` 전체 교체가 엔딩 행을
 * delete + re-insert하므로 **제작자가 엔딩을 한 번 손보기만 해도 그 스토리 회원들의 도달 집계가 통째로
 * 삭제됐다**(이름을 그대로 둬도 행이 새로 생기니 마찬가지). 이름을 키로 올리고 FK를 SET NULL로 낮춰 행이 살아남는다.
 */
@Entity
@Table(
    name = "user_story_ending_reaches",
    uniqueConstraints = [
        UniqueConstraint(
            // 이름이 유니크 키다(V71 contract). 옛 (user_id, story_id, ending_id) 유니크는 엔딩 교체로 id가
            // 갈리면 같은 도달을 못 알아보고 id가 NULL인 행은 아예 못 막아 함께 제거했다.
            name = "uq_user_story_ending_reaches_name",
            columnNames = ["user_id", "story_id", "ending_name_snapshot"],
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

    /**
     * 도달 시점의 엔딩 이름. **읽기·중복 판정의 정본 식별자이자 유니크 키**다(V71 contract).
     *
     * 이름을 키로 올리면 같은 스토리 안에서 시작 설정이 다른 동명 엔딩은 하나로 합쳐지는데, 스토리 상세의
     * reachedEndings가 이름만 담은 평면 목록이라 응답에서 애초에 구분되지 않으므로 잃는 정보가 없다(KNK-462).
     */
    @Column(name = "ending_name_snapshot", nullable = false, length = 100)
    val endingNameSnapshot: String,

    /** 보조 참조. 도달 시점에 라이브 엔딩 행을 찾았으면 그 id, 못 찾았거나 나중에 교체되면 NULL이다. */
    @Column(name = "ending_id")
    val endingId: Long? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
