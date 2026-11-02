package com.knk.manyak.story.home

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.*
import java.util.UUID

interface HomeSectionRepository : JpaRepository<HomeSection, Long> {
    @EntityGraph(attributePaths = ["genres", "stories"])
    fun findAllByOrderBySortOrderAscCreatedAtAscIdAsc(): List<HomeSection>

    @EntityGraph(attributePaths = ["genres", "stories"])
    fun findByIsActiveTrueOrderBySortOrderAscCreatedAtAscIdAsc(): List<HomeSection>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM HomeSection h WHERE h.publicId = :publicId")
    fun findByPublicIdForUpdate(publicId: UUID): HomeSection?

    @Query("SELECT COALESCE(MAX(h.sortOrder), 0) FROM HomeSection h")
    fun maxSortOrder(): Int
}
