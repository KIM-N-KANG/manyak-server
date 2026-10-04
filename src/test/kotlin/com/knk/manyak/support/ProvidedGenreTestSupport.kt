package com.knk.manyak.support

import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository

/** Flyway 없는 H2에서 제작/검수 테스트가 제출하는 제공 장르를 명시적으로 준비한다. */
fun seedProvidedGenres(repository: StoryCreationTagRepository, vararg names: String) {
    names.forEach { name ->
        val key = StoryCreationTag.normalize(name)
        if (repository.findByCategoryAndNormalizedNameIn(SimpleStoryTagCategory.GENRE, listOf(key))
                .none { it.tagSource == StoryCreationTagSource.PREDEFINED }) {
            repository.save(StoryCreationTag(name = name, category = SimpleStoryTagCategory.GENRE,
                tagSource = StoryCreationTagSource.PREDEFINED))
        }
    }
}
