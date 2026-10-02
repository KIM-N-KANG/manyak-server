package com.knk.manyak.image.repository

import com.knk.manyak.image.entity.ImagePreset
import org.springframework.data.jpa.repository.JpaRepository

interface ImagePresetRepository : JpaRepository<ImagePreset, Long>
