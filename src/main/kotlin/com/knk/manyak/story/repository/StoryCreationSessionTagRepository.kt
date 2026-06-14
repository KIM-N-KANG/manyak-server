package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationSessionTag
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationSessionTagRepository : JpaRepository<StoryCreationSessionTag, Long>
