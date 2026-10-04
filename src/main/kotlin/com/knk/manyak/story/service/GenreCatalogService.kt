package com.knk.manyak.story.service

import com.knk.manyak.story.dto.GenreCatalogItem
import com.knk.manyak.story.dto.GenreCatalogResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagAliasRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class GenreCatalogService(
    private val tags: StoryCreationTagRepository,
    private val aliases: StoryCreationTagAliasRepository,
) {
    @Transactional(readOnly = true)
    fun search(query: String?): GenreCatalogResponse {
        if (query != null && query.length > 30) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어는 30자 이하여야 합니다.")
        }
        val catalog = tags.findByTagSourceAndCategoryAndIsActiveTrueOrderBySortOrderAscIdAsc(
            StoryCreationTagSource.PREDEFINED, SimpleStoryTagCategory.GENRE,
        )
        val featured = catalog.filter { it.featuredOrder != null }
            .sortedWith(compareBy<StoryCreationTag> { it.featuredOrder }.thenBy { it.id }).map { it.item() }
        val normalized = StoryCreationTag.normalize(query.orEmpty())
        val aliasNames = if (catalog.isEmpty()) emptyMap() else aliases.findByTagIdIn(catalog.map { it.id })
            .groupBy({ it.tagId }, { it.normalizedAlias })
        val matches = if (normalized.isEmpty()) catalog else {
            val initialsOnly = normalized.all { it in 'ㄱ'..'ㅎ' }
            catalog.mapNotNull { tag ->
                rank(tag.normalizedName, aliasNames[tag.id].orEmpty(), normalized, initialsOnly)?.let { tag to it }
            }.sortedWith(compareBy<Pair<StoryCreationTag, Int>> { it.second }
                .thenBy { it.first.sortOrder }.thenBy { it.first.id }).map { it.first }
        }
        return GenreCatalogResponse(matches.map { it.item() }, featured)
    }

    private fun rank(name: String, aliases: List<String>, query: String, initialsOnly: Boolean): Int? {
        if (initialsOnly) {
            val initials = name.map { c ->
                if (c in '가'..'힣') INITIALS[(c.code - '가'.code) / 588] else c
            }.joinToString("")
            return when {
                initials.startsWith(query) -> 7
                query in initials -> 8
                else -> null
            }
        }
        return when {
            name == query -> 1
            aliases.any { it == query } -> 2
            name.startsWith(query) -> 3
            aliases.any { it.startsWith(query) } -> 4
            query in name -> 5
            aliases.any { query in it } -> 6
            else -> null
        }
    }

    private fun StoryCreationTag.item() = GenreCatalogItem(id, name)

    companion object {
        private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    }
}
