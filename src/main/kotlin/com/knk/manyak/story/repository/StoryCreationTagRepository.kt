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

    /** 직접 추가 태그 find-or-create용 정규화 키 조회(KNK-717). PREDEFINED 연결 판정 때문에 출처로 거르지 않는다. */
    fun findByCategoryAndNormalizedNameIn(
        category: SimpleStoryTagCategory,
        normalizedNames: Collection<String>,
    ): List<StoryCreationTag>
}
