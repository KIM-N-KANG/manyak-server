package com.knk.manyak.story.service

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.StoryVisibility
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
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

    @Transactional(readOnly = true)
    fun getStoriesByIds(request: BatchStoryRequest): List<StorySummaryResponse> {
        val distinctIds = request.storyIds.distinct()
        val storiesById = storyRepository.findAllByIdInAndDeletedAtIsNull(distinctIds).associateBy { it.id }
        return distinctIds
            .mapNotNull { storiesById[it] }
            .map { it.toSummaryResponse() }
    }

    @Transactional(readOnly = true)
    fun getStoryDetail(storyId: Long): StoryDetailResponse {
        val story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
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
            oneLineIntro = story.oneLineIntro.orEmpty(),
            description = story.description,
            genres = story.toGenreNames(),
            hashtags = emptyList(),
            author = null,
            chatCount = 0,
            likeCount = 0,
            startSetting = startSetting?.let {
                StoryStartSettingResponse(
                    name = it.name,
                    prologue = it.prologue,
                    startSituation = it.startSituation,
                )
            },
            recommendedInputs = recommendedInputs,
            visibility = StoryVisibility.PRIVATE,
            status = StoryStatus.PUBLISHED,
            createdAt = story.createdAt,
        )
    }

    /**
     * 스토리를 소프트 삭제한다. 행을 물리 삭제하지 않고 deletedAt만 기록해 자식 데이터(설정·시작 설정·추천 입력)를 보존한다.
     * 이미 삭제됐거나 존재하지 않으면 404로 통일한다.
     */
    @Transactional
    fun deleteStory(storyId: Long) {
        val story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "이야기를 찾을 수 없습니다.")
        // @Transactional 트랜잭션 커밋 시 더티 체킹으로 deletedAt 변경이 반영된다. 명시적 save 불필요.
        story.deletedAt = Instant.now()
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
            oneLineIntro = oneLineIntro.orEmpty(),
            genres = toGenreNames(),
            author = null,
            chatCount = 0,
            likeCount = 0,
            status = StoryStatus.PUBLISHED,
            createdAt = createdAt,
        )
}
