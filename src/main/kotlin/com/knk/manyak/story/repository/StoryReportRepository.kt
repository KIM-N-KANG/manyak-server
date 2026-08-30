package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryReport
import org.springframework.data.jpa.repository.JpaRepository

interface StoryReportRepository : JpaRepository<StoryReport, Long>
