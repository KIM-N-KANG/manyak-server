package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCharacter
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCharacterRepository : JpaRepository<StoryCharacter, Long> {

    // 스토리의 인물을 저장 순서(=컴파일 응답 순서)로 조회한다.
    fun findByStoryIdOrderByIdAsc(storyId: Long): List<StoryCharacter>
}
