package com.knk.manyak.story.service

import com.knk.manyak.global.response.PageResponse
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.dto.GenerateGeneralStoryDraftRequest
import com.knk.manyak.story.dto.GenerateGeneralStoryDraftResponse
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryHelpQuestionResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.SimpleStoryTagResponse
import com.knk.manyak.story.dto.SimpleStorylineResponse
import com.knk.manyak.story.dto.StoryCreateResponse
import com.knk.manyak.story.dto.StoryAuthorResponse
import com.knk.manyak.story.dto.StoryDetailResponse
import com.knk.manyak.story.dto.StoryGenre
import com.knk.manyak.story.dto.StorySort
import com.knk.manyak.story.dto.StoryStatus
import com.knk.manyak.story.dto.StorySummaryResponse
import com.knk.manyak.story.dto.StoryTarget
import com.knk.manyak.story.dto.StoryVisibility
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class StoryService {

    fun generateSimpleStorylines(request: GenerateSimpleStorylinesRequest): GenerateSimpleStorylinesResponse {
        val predefinedTags = request.selectedTagIds.map { tagId ->
            SimpleStoryTagResponse(
                tagId = tagId,
                name = "선택 태그 $tagId",
                category = SimpleStoryTagCategory.OTHER,
            )
        }
        val customTags = request.customTags.mapIndexed { index, tag ->
            SimpleStoryTagResponse(
                tagId = 10_000L + index,
                name = tag.name,
                category = tag.category,
            )
        }

        return GenerateSimpleStorylinesResponse(
            simpleCreationId = 1L,
            selectedTags = predefinedTags + customTags,
            storylines = listOf(
                SimpleStorylineResponse(
                    id = 1L,
                    title = "달빛 아래의 계약",
                    summary = "기억을 잃은 주인공이 금지된 숲에서 자신의 과거와 계약의 비밀을 추적합니다.",
                ),
                SimpleStorylineResponse(
                    id = 2L,
                    title = "왕국의 마지막 편지",
                    summary = "비밀스러운 조력자가 남긴 편지를 따라 사라진 왕국의 진실에 다가갑니다.",
                ),
                SimpleStorylineResponse(
                    id = 3L,
                    title = "깨어난 별의 문",
                    summary = "닫혀 있던 별의 문이 열리며 주인공은 세계를 바꿀 선택 앞에 섭니다.",
                ),
            ),
            helpQuestions = listOf(
                SimpleStoryHelpQuestionResponse(
                    id = 1L,
                    question = "주인공이 반드시 이루고 싶은 목표는 무엇인가요?",
                ),
                SimpleStoryHelpQuestionResponse(
                    id = 2L,
                    question = "주인공을 방해하는 가장 큰 갈등은 무엇인가요?",
                ),
                SimpleStoryHelpQuestionResponse(
                    id = 3L,
                    question = "첫 장면은 어떤 분위기에서 시작되면 좋을까요?",
                ),
            ),
        )
    }

    fun generateGeneralStoryDraft(request: GenerateGeneralStoryDraftRequest): GenerateGeneralStoryDraftResponse =
        GenerateGeneralStoryDraftResponse(
            simpleCreationId = request.simpleCreationId,
            selectedStorylineId = request.selectedStorylineId,
            draft = CreateGeneralStoryRequest(
                coverImageUrl = "https://example.com/covers/moon-contract.png",
                title = "달빛 아래의 계약",
                shortDescription = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기",
                genres = listOf(StoryGenre.FANTASY, StoryGenre.MYSTERY),
                hashtags = listOf("마법", "계약", "기억상실"),
                promptTemplate = "너는 이 세계의 서술자다. 사용자 입력에 따라 장면과 인물 대사를 이어간다.",
                storyInfo = "달이 두 개인 왕국에서 마법은 계약으로만 사용할 수 있습니다.",
                characterInfo = "주인공은 기억을 잃은 마법사이며, 조력자는 정체를 숨긴 왕실 기록관입니다.",
                outputFormat = "상황 묘사와 인물 대사를 구분하고, 마지막에는 사용자가 선택할 수 있는 다음 행동 후보를 제시합니다.",
                startSituationName = "비 내리는 여관의 편지",
                conversationPrologue = "비가 내리는 밤, 당신은 여관 2층 방에서 봉인된 편지를 발견합니다.",
                recommendedInputs = listOf("편지를 열어본다", "창밖의 인기척을 확인한다", "여관 주인을 찾아간다"),
                detailedIntroduction = "계약 마법이 지배하는 왕국에서 잃어버린 기억과 사라진 가족의 비밀을 추적하는 인터랙티브 판타지입니다.",
                target = StoryTarget.TEEN,
                visibility = StoryVisibility.PUBLIC,
                status = StoryStatus.DRAFT,
            ),
        )

    fun createGeneralStory(request: CreateGeneralStoryRequest): StoryCreateResponse =
        StoryCreateResponse(
            id = 2L,
            mode = "GENERAL",
            title = request.title,
            status = request.status,
            createdAt = Instant.now(),
        )

    fun getStories(
        genre: StoryGenre?,
        sort: StorySort,
        page: Int,
        size: Int,
    ): PageResponse<StorySummaryResponse> {
        val story = sampleStory(
            id = 1L,
            genre = genre ?: StoryGenre.FANTASY,
            status = StoryStatus.PUBLISHED,
        )

        return PageResponse(
            content = listOf(story),
            page = page,
            size = size,
            totalElements = 1,
            totalPages = 1,
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
            author = StoryAuthorResponse(
                id = 1L,
                nickname = "manyak_writer",
                profileImageUrl = "https://example.com/profile.png",
            ),
            chatCount = 128,
            likeCount = 32,
            startSituationName = "비 내리는 여관의 편지",
            conversationPrologue = "비가 내리는 밤, 당신은 여관 2층 방에서 봉인된 편지를 발견합니다.",
            recommendedInputs = listOf("편지를 열어본다", "창밖의 인기척을 확인한다", "여관 주인을 찾아간다"),
            visibility = StoryVisibility.PUBLIC,
            status = StoryStatus.PUBLISHED,
            createdAt = Instant.now(),
        )

    fun getMyStories(
        status: StoryStatus?,
        page: Int,
        size: Int,
    ): PageResponse<StorySummaryResponse> {
        val story = sampleStory(
            id = 2L,
            genre = StoryGenre.MYSTERY,
            status = status ?: StoryStatus.DRAFT,
        )

        return PageResponse(
            content = listOf(story),
            page = page,
            size = size,
            totalElements = 1,
            totalPages = 1,
        )
    }

    private fun sampleStory(
        id: Long,
        genre: StoryGenre,
        status: StoryStatus,
    ): StorySummaryResponse =
        StorySummaryResponse(
            id = id,
            title = "달빛 아래의 계약",
            summary = "기억을 잃은 마법사가 금지된 숲에서 자신의 과거를 추적하는 이야기",
            genres = listOf(genre),
            authorNickname = "manyak_writer",
            chatCount = 128,
            likeCount = 32,
            status = status,
            createdAt = Instant.now(),
        )
}
