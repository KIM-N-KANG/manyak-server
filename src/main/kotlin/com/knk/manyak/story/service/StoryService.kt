package com.knk.manyak.story.service

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.StoryVisibility
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class StoryService(
    private val storyRepository: StoryRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
) {

    @Transactional(readOnly = true)
    fun getStoriesByIds(request: BatchStoryRequest): List<StorySummaryResponse> {
        val distinctIds = request.storyIds.distinct()
        val storiesById = storyRepository.findAllById(distinctIds).associateBy { it.id }
        return distinctIds
            .mapNotNull { storiesById[it] }
            .map { it.toSummaryResponse() }
    }

    @Transactional(readOnly = true)
    fun getStoryDetail(storyId: Long): StoryDetailResponse {
        val story = storyRepository.findByIdOrNull(storyId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "이야기를 찾을 수 없습니다.")

        val startSetting = storyStartSettingRepository.findByStoryId(storyId)
        val recommendedInputs = startSetting
            ?.let { storySuggestedInputRepository.findByStartSettingIdOrderByInputOrderAsc(it.id) }
            ?.map { it.inputText }
            ?: emptyList()

        return StoryDetailResponse(
            id = story.id,
            coverImageUrl = null,
            title = story.title,
            shortDescription = story.oneLineIntro.orEmpty(),
            detailedIntroduction = story.description,
            genres = story.toGenreNames(),
            hashtags = emptyList(),
            author = null,
            chatCount = 0,
            likeCount = 0,
            startSituationName = startSetting?.name.orEmpty(),
            conversationPrologue = startSetting?.prologue.orEmpty(),
            recommendedInputs = recommendedInputs,
            visibility = StoryVisibility.PRIVATE,
            status = StoryStatus.PUBLISHED,
            createdAt = story.createdAt,
        )
    }

    private fun Story.toGenreNames(): List<String> =
        genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun Story.toSummaryResponse(): StorySummaryResponse =
        StorySummaryResponse(
            id = id,
            title = title,
            summary = oneLineIntro.orEmpty(),
            genres = toGenreNames(),
            authorNickname = null,
            chatCount = 0,
            likeCount = 0,
            status = StoryStatus.PUBLISHED,
            createdAt = createdAt,
        )
}
