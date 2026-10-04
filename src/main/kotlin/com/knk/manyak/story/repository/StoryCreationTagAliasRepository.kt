package com.knk.manyak.story.repository

import com.knk.manyak.story.entity.StoryCreationTagAlias
import org.springframework.data.jpa.repository.JpaRepository

interface StoryCreationTagAliasRepository : JpaRepository<StoryCreationTagAlias, Long> {
    fun findByTagIdIn(tagIds: Collection<Long>): List<StoryCreationTagAlias>
}
