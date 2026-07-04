package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationTagRepository : JpaRepository<StoryCreationTag, Long> {
    fun findByTagSourceAndIsActiveTrueOrderByCategoryAscSortOrderAscIdAsc(
        tagSource: StoryCreationTagSource,
    ): List<StoryCreationTag>

    fun findByIdInAndTagSourceAndIsActiveTrue(
        ids: Collection<Long>,
        tagSource: StoryCreationTagSource,
    ): List<StoryCreationTag>

    fun findByTagSourceAndCategoryAndNameIn(
        tagSource: StoryCreationTagSource,
        category: SimpleStoryTagCategory,
        names: Collection<String>,
    ): List<StoryCreationTag>
}
