package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryStartSetting
import org.springframework.data.jpa.repository.JpaRepository

interface StoryStartSettingRepository : JpaRepository<StoryStartSetting, Long>
