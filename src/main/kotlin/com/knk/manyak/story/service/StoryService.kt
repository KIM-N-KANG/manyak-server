package com.knk.manyak.story.service

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryGenre
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
import java.time.Instant

@Service
class StoryService(
    private val storyRepository: StoryRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
) {

    fun getStoriesByIds(request: BatchStoryRequest): List<StorySummaryResponse> =
        request.storyIds.mapIndexed { index, storyId ->
            sampleStory(
                id = storyId,
                genre = if (index % 2 == 0) StoryGenre.FANTASY else StoryGenre.MYSTERY,
                status = StoryStatus.PUBLISHED,
                title = if (index % 2 == 0) "달빛 아래의 계약" else "왕국의 마지막 편지",
            )
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

    private fun sampleStory(
        id: Long,
        genre: StoryGenre,
        status: StoryStatus,
        title: String = "달빛 아래의 계약",
    ): StorySummaryResponse =
        StorySummaryResponse(
            id = id,
            title = title,
            summary = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기",
            genres = listOf(genre),
            authorNickname = null,
            chatCount = 128,
            likeCount = 32,
            status = status,
            createdAt = Instant.now(),
        )
}
