package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.ArtStyle
import org.springframework.data.jpa.repository.JpaRepository

interface ArtStyleRepository : JpaRepository<ArtStyle, String> {
    fun findByIsActiveTrueOrderBySortOrderAsc(): List<ArtStyle>
    fun existsByCodeAndIsActiveTrue(code: String): Boolean
}
