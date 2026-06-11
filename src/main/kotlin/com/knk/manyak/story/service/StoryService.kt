package com.knk.manyak.story.service

import com.knk.manyak.story.dto.BatchStoryRequest
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryHelpQuestionResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.SimpleStoryTagResponse
import com.knk.manyak.story.dto.SimpleStorylineResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryGenre
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.StoryVisibility
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StoryService {

    fun generateSimpleStorylines(request: GenerateSimpleStorylinesRequest): GenerateSimpleStorylinesResponse {
        val selectedTags = request.selectedTagIds.map { tagId ->
            SimpleStoryTagResponse(
                tagId = tagId,
                name = "선택 태그 $tagId",
                category = SimpleStoryTagCategory.OTHER,
            )
        }

        return GenerateSimpleStorylinesResponse(
            simpleCreationId = 1L,
            selectedTags = selectedTags,
            storylines = listOf(
                SimpleStorylineResponse(
                    id = 1L,
                    title = "달빛 아래의 계약",
                    summary = "기억을 잃은 주인공이 금지된 숲에서 자신의 과거와 계약의 비밀을 추적합니다.",
                    helpQuestions = listOf(
                        SimpleStoryHelpQuestionResponse(
                            id = 1L,
                            question = "주인공이 반드시 되찾고 싶은 것은 무엇인가요?",
                        ),
                        SimpleStoryHelpQuestionResponse(
                            id = 2L,
                            question = "계약의 대가는 무엇이면 좋을까요?",
                        ),
                        SimpleStoryHelpQuestionResponse(
                            id = 3L,
                            question = "첫 장면은 어디에서 시작되면 좋을까요?",
                        ),
                    ),
                ),
                SimpleStorylineResponse(
                    id = 2L,
                    title = "왕국의 마지막 편지",
                    summary = "비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가갑니다.",
                    helpQuestions = listOf(
                        SimpleStoryHelpQuestionResponse(
                            id = 4L,
                            question = "편지를 남긴 인물은 누구인가요?",
                        ),
                        SimpleStoryHelpQuestionResponse(
                            id = 5L,
                            question = "왕국이 사라진 이유는 무엇인가요?",
                        ),
                        SimpleStoryHelpQuestionResponse(
                            id = 6L,
                            question = "주인공은 진실을 공개할까요, 숨길까요?",
                        ),
                    ),
                ),
                SimpleStorylineResponse(
                    id = 3L,
                    title = "깨어난 별의 문",
                    summary = "닫혀 있던 별의 문이 열리며 주인공은 세계를 바꿀 선택 앞에 섭니다.",
                    helpQuestions = listOf(
                        SimpleStoryHelpQuestionResponse(
                            id = 7L,
                            question = "별의 문 너머에는 무엇이 있나요?",
                        ),
                        SimpleStoryHelpQuestionResponse(
                            id = 8L,
                            question = "주인공과 함께할 동료는 어떤 인물인가요?",
                        ),
                        SimpleStoryHelpQuestionResponse(
                            id = 9L,
                            question = "마지막 선택은 어떤 희생을 요구하나요?",
                        ),
                    ),
                ),
            ),
        )
    }

    fun createSimpleStory(request: CreateSimpleStoryRequest): SimpleStoryCreateResponse =
        SimpleStoryCreateResponse(storyId = request.storylineId + 100L)

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
