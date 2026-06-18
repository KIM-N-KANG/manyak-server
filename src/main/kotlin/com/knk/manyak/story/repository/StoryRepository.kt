package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.Story
import org.springframework.data.jpa.repository.JpaRepository

interface StoryRepository : JpaRepository<Story, Long>
