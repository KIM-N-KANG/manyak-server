package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryImage
import org.springframework.data.jpa.repository.JpaRepository

interface StoryImageRepository : JpaRepository<StoryImage, Long> {
    fun findByStoryIdOrderByIdAsc(storyId: Long): List<StoryImage>
}
