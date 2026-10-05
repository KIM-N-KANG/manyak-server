package com.knk.manyak.story.service

import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.ApiErrorDetail
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationTagRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/** 제출 시에만 호출한다. 승인, 저장된 제출본 회수와 간편 제작 컴파일에는 적용하지 않는다. */
@Service
class GenreInputValidator(private val tags: StoryCreationTagRepository) {
    fun normalize(input: List<String>, existingGenre: String? = null): List<String> {
        // 중복 제거와 trim 전에 기존 개수와 길이 제한을 검사한다. 서비스 직접 호출도 같은 계약이다.
        if (input.size !in 1..8 || input.any { it.length > 30 }) {
            throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", INVALID_MESSAGE,
                details = listOf(ApiErrorDetail("genres", "장르는 1개 이상 8개 이하, 각 30자 이하여야 합니다.")))
        }
        val provided = tags.findByTagSourceAndIsActiveTrueOrderByCategoryAscSortOrderAscIdAsc(StoryCreationTagSource.PREDEFINED)
            .filter { it.category == SimpleStoryTagCategory.GENRE }.associateBy { it.normalizedName }
        val existing = existingGenre.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
            .associateBy { StoryCreationTag.normalize(it) }
        val invalid = mutableListOf<ApiErrorDetail>()
        val selected = linkedMapOf<String, String>()
        input.forEachIndexed { index, value ->
            val key = StoryCreationTag.normalize(value.trim())
            val name = if (key.isEmpty()) null else provided[key]?.name ?: existing[key]
            if (name == null) invalid += ApiErrorDetail("genres[$index]", "제공 장르에서 선택해 주세요.")
            else selected.putIfAbsent(key, name)
        }
        if (invalid.isNotEmpty()) {
            throw CodedResponseStatusException(HttpStatus.BAD_REQUEST, ApiErrorCodes.INVALID_GENRE, INVALID_MESSAGE, details = invalid)
        }
        return selected.values.toList()
    }

    companion object {
        private const val INVALID_MESSAGE = "요청 값이 올바르지 않습니다."
    }
}
