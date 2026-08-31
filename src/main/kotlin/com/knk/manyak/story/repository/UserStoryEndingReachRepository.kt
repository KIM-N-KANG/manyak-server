package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.UserStoryEndingReach
import org.springframework.data.jpa.repository.JpaRepository

interface UserStoryEndingReachRepository : JpaRepository<UserStoryEndingReach, Long> {

    // 회원이 한 스토리에서 도달한 엔딩 집계(스토리 상세 reachedEndings 소스).
    fun findByUserIdAndStoryId(userId: Long, storyId: Long): List<UserStoryEndingReach>

    // 최초 1회 upsert 가드. 이미 도달한 (회원, 스토리, 엔딩 이름)이면 다시 저장하지 않는다.
    // id가 아니라 이름으로 판정한다 — 엔딩 교체로 id가 갈려도 같은 도달임을 알아보게 하려는 것이 V70의 목적이다.
    fun existsByUserIdAndStoryIdAndEndingNameSnapshot(userId: Long, storyId: Long, endingNameSnapshot: String): Boolean

    // id 기준 가드. 롤링 배포 창에 구버전 태스크가 쓴 행은 ending_name_snapshot이 NULL이라 위 이름 가드로는
    // 찾지 못하는데, 옛 유니크(user_id, story_id, ending_id)는 아직 살아 있어 같은 id로 다시 넣으면 위반이 난다.
    // 그래서 이름과 id **양쪽**으로 확인한다. KNK-1084(후속 contract)의 재백필이 끝나면 이 가드는 불필요해진다.
    fun existsByUserIdAndStoryIdAndEndingId(userId: Long, storyId: Long, endingId: Long): Boolean
}
