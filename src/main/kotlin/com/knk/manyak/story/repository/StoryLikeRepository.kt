package com.knk.manyak.story.repository

import com.knk.manyak.story.dto.StoryLikeCountProjection
import com.knk.manyak.story.entity.StoryLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface StoryLikeRepository : JpaRepository<StoryLike, Long> {

    fun existsByUserIdAndStoryId(userId: Long, storyId: Long): Boolean

    // 파생 쿼리는 count(id)라 힙을 읽는다. COUNT(*)는 idx_story_likes_story만으로 센다(KNK-2076).
    @Query("SELECT COUNT(*) FROM StoryLike l WHERE l.storyId = :storyId")
    fun countByStoryId(@Param("storyId") storyId: Long): Long

    /** 좋아요 취소. 지울 행이 없어도 조용히 통과한다(멱등 계약). */
    @Transactional
    @Modifying
    @Query("DELETE FROM StoryLike l WHERE l.userId = :userId AND l.storyId = :storyId")
    fun deleteByUserIdAndStoryId(@Param("userId") userId: Long, @Param("storyId") storyId: Long)

    // 목록 응답용 배치 집계(N+1 방지). 좋아요가 없는 스토리는 결과에 빠지므로 호출부가 0으로 보정한다.
    @Query("SELECT l.storyId AS storyId, COUNT(*) AS likeCount FROM StoryLike l WHERE l.storyId IN :storyIds GROUP BY l.storyId")
    fun countByStoryIds(@Param("storyIds") storyIds: Collection<Long>): List<StoryLikeCountProjection>
}
