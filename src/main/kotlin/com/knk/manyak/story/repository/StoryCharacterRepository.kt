package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCharacter
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StoryCharacterRepository : JpaRepository<StoryCharacter, Long> {
    fun findByStoryIdOrderByIdAsc(storyId: Long): List<StoryCharacter>

    /**
     * 이미지가 배정됐고 그 이미지가 지금 활성인 인물만 반환한다(매 턴 AI 요청 재료).
     *
     * 배정 실패(`image_key` NULL)와 비활성 이미지는 조인에서 자연히 빠진다 — 그 인물은 이미지 없이 진행한다.
     */
    @Query(
        """
        select sc from StoryCharacter sc, ImagePreset p
        where sc.imageKey = p.imageKey
          and sc.storyId = :storyId
          and p.deactivatedAt is null
        order by sc.id
        """,
    )
    fun findWithActiveImageByStoryId(@Param("storyId") storyId: Long): List<StoryCharacter>
}
