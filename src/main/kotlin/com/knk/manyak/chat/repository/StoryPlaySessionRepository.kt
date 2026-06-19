package com.knk.manyak.chat.repository

import com.knk.manyak.chat.entity.StoryPlaySession
import org.springframework.data.jpa.repository.JpaRepository

interface StoryPlaySessionRepository : JpaRepository<StoryPlaySession, Long>
