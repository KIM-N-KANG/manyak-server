package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StorySetting
import org.springframework.data.jpa.repository.JpaRepository

interface StorySettingRepository : JpaRepository<StorySetting, Long> {
    fun findByStoryId(storyId: Long): StorySetting?

    // 수정 폼 묶음 조립용. 스토리당 한 행이다.
    fun findByStoryIdIn(storyIds: Collection<Long>): List<StorySetting>
}
