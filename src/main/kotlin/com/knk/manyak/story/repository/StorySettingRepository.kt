package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StorySetting
import org.springframework.data.jpa.repository.JpaRepository

interface StorySettingRepository : JpaRepository<StorySetting, Long>
