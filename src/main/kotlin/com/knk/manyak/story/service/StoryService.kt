package com.knk.manyak.story.service

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryGenre
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.StoryVisibility
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StoryService {

    fun getStoriesByIds(request: BatchStoryRequest): List<StorySummaryResponse> =
        request.storyIds.mapIndexed { index, storyId ->
            sampleStory(
                id = storyId,
                genre = if (index % 2 == 0) StoryGenre.FANTASY else StoryGenre.MYSTERY,
                status = StoryStatus.PUBLISHED,
                title = if (index % 2 == 0) "달빛 아래의 계약" else "왕국의 마지막 편지",
            )
        }

    fun getStoryDetail(storyId: Long): StoryDetailResponse =
        StoryDetailResponse(
            id = storyId,
            coverImageUrl = "https://example.com/covers/moon-contract.png",
            title = "달빛 아래의 계약",
            shortDescription = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기",
            detailedIntroduction = "계약 마법이 지배하는 왕국에서 잃어버린 기억과 사라진 가족의 비밀을 추적하는 인터랙티브 판타지입니다.",
            genres = listOf(StoryGenre.FANTASY, StoryGenre.MYSTERY),
            hashtags = listOf("마법", "계약", "기억상실"),
            author = null,
            chatCount = 128,
            likeCount = 32,
            startSituationName = "비 내리는 여관의 편지",
            conversationPrologue = "비가 내리는 밤, 당신은 여관 2층 방에서 봉인된 편지를 발견합니다.",
            recommendedInputs = listOf("편지를 열어본다", "창밖의 인기척을 확인한다", "여관 주인을 찾아간다"),
            visibility = StoryVisibility.PUBLIC,
            status = StoryStatus.PUBLISHED,
            createdAt = Instant.now(),
        )

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
