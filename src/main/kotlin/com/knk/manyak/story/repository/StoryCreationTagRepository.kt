package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationTagRepository : JpaRepository<StoryCreationTag, Long> {
    fun findByTagSourceAndIsActiveTrueOrderByTagTypeAscSortOrderAscIdAsc(
        tagSource: StoryCreationTagSource,
    ): List<StoryCreationTag>

    fun findByIdInAndTagSourceAndIsActiveTrue(
        ids: Collection<Long>,
        tagSource: StoryCreationTagSource,
    ): List<StoryCreationTag>
}
