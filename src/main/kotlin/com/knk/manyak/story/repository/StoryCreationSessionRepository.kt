package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationSession
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StoryCreationSessionRepository : JpaRepository<StoryCreationSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StoryCreationSession s WHERE s.id = :id")
    fun findByIdForUpdate(id: Long): StoryCreationSession?

    /**
     * 스토리 마이그레이션(KNK-389) 시, 클레임한 스토리에 연결된 생성 세션(user_id NULL)의 소유권도 함께 클레임한다.
     *
     * 세션 소유자가 NULL이면 storyline 평가/취소가 익명 취급으로 열리므로([StorylineRatingService]), 스토리와 소유권을 일치시킨다.
     */
    @Modifying
    @Query("UPDATE StoryCreationSession s SET s.userId = :userId WHERE s.storyId = :storyId AND s.userId IS NULL")
    fun claimByStoryId(@Param("storyId") storyId: Long, @Param("userId") userId: Long): Int

    /**
     * 완성 스토리 → 이 스토리를 만든 간편 제작 세션 역조회(KNK-751). 채팅 생성 시 creation_id를 1회 해석하는 데만 쓴다.
     * 한 스토리에 세션은 하나지만(compile이 story_id를 박는다) 계약상 첫 행으로 고정한다. 일반 제작 스토리는 없어서 null이다.
     */
    fun findFirstByStoryIdOrderByIdAsc(storyId: Long): StoryCreationSession?
}
