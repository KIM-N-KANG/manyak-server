package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.UserStoryEndingReach
import org.springframework.data.jpa.repository.JpaRepository

interface UserStoryEndingReachRepository : JpaRepository<UserStoryEndingReach, Long> {

    // 회원이 한 스토리에서 도달한 엔딩 집계(스토리 상세 reachedEndings 소스).
    fun findByUserIdAndStoryId(userId: Long, storyId: Long): List<UserStoryEndingReach>

    // 최초 1회 upsert 가드. 이미 도달한 (회원, 스토리, 엔딩)이면 다시 저장하지 않는다.
    fun existsByUserIdAndStoryIdAndEndingId(userId: Long, storyId: Long, endingId: Long): Boolean
}
