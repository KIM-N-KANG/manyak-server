package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StorySuggestedInput
import org.springframework.data.jpa.repository.JpaRepository

interface StorySuggestedInputRepository : JpaRepository<StorySuggestedInput, Long>
