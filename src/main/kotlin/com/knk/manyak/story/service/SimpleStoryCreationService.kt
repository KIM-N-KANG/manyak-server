package com.knk.manyak.story.service

import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryHelpQuestionResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.SimpleStoryTagListItemResponse
import com.knk.manyak.story.dto.SimpleStoryTagResponse
import com.knk.manyak.story.dto.SimpleStorylineResponse
import com.knk.manyak.story.entity.StoryCreationExample
import com.knk.manyak.story.entity.StoryCreationExampleQuestion
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.repository.StoryCreationExampleQuestionRepository
import com.knk.manyak.story.repository.StoryCreationExampleRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class SimpleStoryCreationService(
    private val storyCreationTagRepository: StoryCreationTagRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationSessionTagRepository: StoryCreationSessionTagRepository,
    private val storyCreationExampleRepository: StoryCreationExampleRepository,
    private val storyCreationExampleQuestionRepository: StoryCreationExampleQuestionRepository,
    private val storyAiClient: StoryAiClient,
) {

    @Transactional(readOnly = true)
    fun getSimpleStoryTags(): List<SimpleStoryTagListItemResponse> =
        storyCreationTagRepository
            .findByTagSourceAndIsActiveTrueOrderByTagTypeAscSortOrderAscIdAsc(StoryCreationTagSource.PREDEFINED)
            .map { tag ->
                SimpleStoryTagListItemResponse(
                    tagId = tag.id,
                    name = tag.name,
                    category = tag.tagType,
                )
            }

    @Transactional
    fun generateSimpleStorylines(request: GenerateSimpleStorylinesRequest): GenerateSimpleStorylinesResponse {
        val predefinedTags = findSelectedPredefinedTags(request.selectedTagIds)
        val customTags = storyCreationTagRepository.saveAll(
            request.customTags.map { tag ->
                StoryCreationTag(
                    tagType = tag.category,
                    name = tag.name.trim(),
                    tagSource = StoryCreationTagSource.CUSTOM,
                    sortOrder = 0,
                    isActive = true,
                )
            },
        )
        val tags = predefinedTags + customTags
        val creationSession = storyCreationSessionRepository.save(
            StoryCreationSession(status = StoryCreationSessionStatus.STORYLINES_GENERATED),
        )
        storyCreationSessionTagRepository.saveAll(
            tags.map { tag ->
                StoryCreationSessionTag(
                    creationSession = creationSession,
                    tag = tag,
                )
            },
        )

        val aiResponse = try {
            storyAiClient.createStorylines(tags.toAiStorylinesRequest())
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리라인 생성 요청에 실패했습니다.", exception)
        }

        val storylines = aiResponse.stories.mapIndexed { index, story ->
            val example = storyCreationExampleRepository.save(
                StoryCreationExample(
                    creationSession = creationSession,
                    exampleText = story.story,
                    exampleOrder = (index + 1).toShort(),
                ),
            )
            val questions = storyCreationExampleQuestionRepository.saveAll(
                story.questions.mapIndexed { questionIndex, question ->
                    StoryCreationExampleQuestion(
                        example = example,
                        question = question,
                        questionOrder = (questionIndex + 1).toShort(),
                    )
                },
            )

            SimpleStorylineResponse(
                id = example.id,
                story = example.exampleText,
                helpQuestions = questions.map { question ->
                    SimpleStoryHelpQuestionResponse(
                        id = question.id,
                        question = question.question,
                    )
                },
            )
        }

        return GenerateSimpleStorylinesResponse(
            simpleCreationId = creationSession.id,
            selectedTags = tags.map { it.toTagResponse() },
            storylines = storylines,
        )
    }

    fun createSimpleStory(request: CreateSimpleStoryRequest): SimpleStoryCreateResponse =
        SimpleStoryCreateResponse(storyId = request.storylineId + 100L)

    private fun findSelectedPredefinedTags(selectedTagIds: List<Long>): List<StoryCreationTag> {
        val distinctTagIds = selectedTagIds.distinct()
        if (distinctTagIds.isEmpty()) {
            return emptyList()
        }

        val tagsById = storyCreationTagRepository
            .findByIdInAndTagSourceAndIsActiveTrue(distinctTagIds, StoryCreationTagSource.PREDEFINED)
            .associateBy { it.id }
        val missingTagIds = distinctTagIds.filterNot { tagsById.containsKey(it) }
        if (missingTagIds.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "사용할 수 없는 태그 ID가 포함되어 있습니다: ${missingTagIds.joinToString(",")}",
            )
        }

        return distinctTagIds.map { tagsById.getValue(it) }
    }

    private fun List<StoryCreationTag>.toAiStorylinesRequest(): AiStorylinesRequest =
        AiStorylinesRequest(
            genre_tags = filter { it.tagType == SimpleStoryTagCategory.GENRE }.map { it.name },
            protagonist_tags = filter { it.tagType == SimpleStoryTagCategory.PROTAGONIST }.map { it.name },
            supporting_tags = filter { it.tagType == SimpleStoryTagCategory.SUPPORTING_CHARACTER }.map { it.name },
        )

    private fun StoryCreationTag.toTagResponse(): SimpleStoryTagResponse =
        SimpleStoryTagResponse(
            tagId = id,
            name = name,
            category = tagType,
        )
}
