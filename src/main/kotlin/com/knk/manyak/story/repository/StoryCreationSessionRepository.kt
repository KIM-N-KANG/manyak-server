package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationSession
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationSessionRepository : JpaRepository<StoryCreationSession, Long>
