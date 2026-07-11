package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryMainEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional

interface StoryMainEventRepository : JpaRepository<StoryMainEvent, Long> {

    // 스토리의 주요 사건을 표시 순서로 조회한다(상세·목록 응답, 교체 저장 후 반환).
    fun findByStoryIdOrderBySortOrderAsc(storyId: Long): List<StoryMainEvent>

    // 채팅 런타임: AI가 이름으로 지목한 목표·완결 사건을 id로 해소한다(이름은 사건 식별자, §4-3-10).
    fun findFirstByStoryIdAndName(storyId: Long, name: String): StoryMainEvent?

    // 교체 저장(PUT)에서 기존 주요 사건을 먼저 지운다. 반환값은 삭제한 행 수.
    @Transactional
    fun deleteByStoryId(storyId: Long): Long
}
