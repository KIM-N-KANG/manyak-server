package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface StoryLikeRepository : JpaRepository<StoryLike, Long> {

    fun existsByUserIdAndStoryId(userId: Long, storyId: Long): Boolean

    fun countByStoryId(storyId: Long): Long

    /** 좋아요 취소. 지울 행이 없어도 조용히 통과한다(멱등 계약). */
    @Transactional
    @Modifying
    @Query("DELETE FROM StoryLike l WHERE l.userId = :userId AND l.storyId = :storyId")
    fun deleteByUserIdAndStoryId(@Param("userId") userId: Long, @Param("storyId") storyId: Long)

    // 목록 응답용 배치 집계(N+1 방지). 좋아요가 없는 스토리는 결과에 빠지므로 호출부가 0으로 보정한다.
    @Query("SELECT l.storyId AS storyId, COUNT(l) AS likeCount FROM StoryLike l WHERE l.storyId IN :storyIds GROUP BY l.storyId")
    fun countByStoryIds(@Param("storyIds") storyIds: Collection<Long>): List<StoryLikeCountProjection>
}

/** 스토리별 좋아요 수 배치 집계 결과. */
interface StoryLikeCountProjection {
    val storyId: Long
    val likeCount: Long
}
