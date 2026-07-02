package com.knk.manyak.story.service

import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryRecommendedInfoResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.SimpleStoryTagListItemResponse
import com.knk.manyak.story.dto.SimpleStoryTagResponse
import com.knk.manyak.story.dto.SimpleStorylineResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationExample
import com.knk.manyak.story.entity.StoryCreationExampleRecommendedInfo
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.repository.StoryCreationExampleRecommendedInfoRepository
import com.knk.manyak.story.repository.StoryCreationExampleRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class SimpleStoryCreationService(
    private val storyCreationTagRepository: StoryCreationTagRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationSessionTagRepository: StoryCreationSessionTagRepository,
    private val storyCreationExampleRepository: StoryCreationExampleRepository,
    private val storyCreationExampleRecommendedInfoRepository: StoryCreationExampleRecommendedInfoRepository,
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val storyAiClient: StoryAiClient,
    private val structuredLogger: StructuredLogger,
    private val aiCallRecorder: AiCallRecorder,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private companion object {
        // AI 응답이 컬럼 길이를 초과해도 트랜잭션이 실패하지 않도록 방어적으로 자른다. (stories 컬럼 정의와 일치)
        const val STORY_TITLE_MAX_LENGTH = 100
        const val STORY_ONE_LINE_INTRO_MAX_LENGTH = 255
    }

    @Transactional(readOnly = true)
    fun getSimpleStoryTags(): List<SimpleStoryTagListItemResponse> =
        storyCreationTagRepository
            .findByTagSourceAndIsActiveTrueOrderByCategoryAscSortOrderAscIdAsc(StoryCreationTagSource.PREDEFINED)
            .map { tag ->
                SimpleStoryTagListItemResponse(
                    id = tag.id,
                    name = tag.name,
                    category = tag.category,
                )
            }

    fun generateSimpleStorylines(
        request: GenerateSimpleStorylinesRequest,
        userId: Long? = null,
    ): GenerateSimpleStorylinesResponse {
        val predefinedTags = findSelectedPredefinedTags(request.selectedTagIds)
        val customTagDrafts = request.customTags.map { tag ->
            StoryCreationTagDraft(
                category = tag.category,
                name = tag.name.trim(),
            )
        }.distinctBy { it.key }
        val aiRequestTags = predefinedTags.map { tag ->
            StoryCreationTagDraft(
                category = tag.category,
                name = tag.name,
            )
        } + customTagDrafts

        val aiResponse = try {
            aiCallRecorder.record(
                AiCallContext(feature = AiCallFeature.STORYLINE_GENERATION),
                meta = { it.meta?.toAiCallMeta() },
            ) {
                storyAiClient.createStorylines(aiRequestTags.toAiStorylinesRequest())
            }.result
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리라인 생성 요청에 실패했습니다.", exception)
        }

        return transactionTemplate.execute {
            val customTags = findOrCreateCustomTags(customTagDrafts)
            val tags = predefinedTags + customTags
            val creationSession = storyCreationSessionRepository.save(
                StoryCreationSession(userId = userId, status = StoryCreationSessionStatus.STORYLINES_GENERATED),
            )
            storyCreationSessionTagRepository.saveAll(
                tags.map { tag ->
                    StoryCreationSessionTag(
                        creationSession = creationSession,
                        tag = tag,
                    )
                },
            )

            val examples = storyCreationExampleRepository.saveAll(
                aiResponse.stories.mapIndexed { index, story ->
                    StoryCreationExample(
                        creationSession = creationSession,
                        exampleText = story.story,
                        exampleOrder = (index + 1).toShort(),
                    )
                },
            )
            val recommendedInfos = storyCreationExampleRecommendedInfoRepository.saveAll(
                examples.zip(aiResponse.stories).flatMap { (example, story) ->
                    story.recommendedInfos.mapIndexed { infoIndex, info ->
                        StoryCreationExampleRecommendedInfo(
                            example = example,
                            infoText = info,
                            infoOrder = (infoIndex + 1).toShort(),
                        )
                    }
                }
            ).groupBy { info -> info.example.id }

            val storylines = examples.map { example ->
                SimpleStorylineResponse(
                    id = example.id,
                    story = example.exampleText,
                    recommendedInfos = recommendedInfos[example.id].orEmpty().map { info ->
                        SimpleStoryRecommendedInfoResponse(
                            id = info.id,
                            text = info.infoText,
                        )
                    },
                )
            }

            GenerateSimpleStorylinesResponse(
                simpleCreationId = creationSession.id,
                selectedTags = tags.map { it.toTagResponse() },
                storylines = storylines,
            )
        } ?: throw IllegalStateException("Storyline creation transaction result is empty")
    }

    fun createSimpleStory(request: CreateSimpleStoryRequest, userId: Long? = null): SimpleStoryCreateResponse {
        val startNanos = System.nanoTime()
        structuredLogger.event("story_create_requested", "creation_id" to request.simpleCreationId)
        try {
            val outcome = doCreateSimpleStory(request, userId)
            structuredLogger.event(
                "story_created",
                "story_id" to outcome.response.id,
                "ai_call_log_id" to outcome.aiCallLogId,
                "duration_ms" to (System.nanoTime() - startNanos) / 1_000_000,
            )
            return outcome.response
        } catch (exception: Exception) {
            structuredLogger.event(
                "story_create_failed",
                "error_code" to storyErrorCode(exception),
                "duration_ms" to (System.nanoTime() - startNanos) / 1_000_000,
            )
            throw exception
        }
    }

    private fun storyErrorCode(exception: Exception): String = when (exception) {
        is ResponseStatusException ->
            HttpStatus.resolve(exception.statusCode.value())?.name ?: exception.statusCode.toString()
        else -> exception::class.simpleName ?: "UNKNOWN"
    }

    private fun doCreateSimpleStory(request: CreateSimpleStoryRequest, userId: Long?): StoryCreationOutcome {
        val session = storyCreationSessionRepository.findById(request.simpleCreationId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "간편 제작 진행 정보를 찾을 수 없습니다.")
            }
        if (session.status == StoryCreationSessionStatus.STORY_CREATED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
        }

        // 다단계 간편 제작 소유권 강제(Codex PR #76 P2): AI 호출(비용) 전에 검사·거부한다.
        // - 익명 세션(소유자 없음): 이 finalize 요청의 인증 사용자(또는 익명)에 귀속.
        // - 소유 세션(소유자 있음): 같은 사용자만 완료 가능. 다른 사용자/익명(만료·무효 토큰 포함)이 simpleCreationId로
        //   남의 진행을 가로채 자기 user_id로 기록하거나, 소유 세션을 익명으로 떨어뜨리는 것을 막는다.
        val attributedUserId = when {
            session.userId == null -> userId
            session.userId == userId -> userId
            else -> throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "본인이 시작한 간편 제작만 완료할 수 있습니다.",
            )
        }

        val selectedStoryline = storyCreationExampleRepository
            .findByIdAndCreationSessionId(request.storylineId, request.simpleCreationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 스토리라인을 찾을 수 없습니다.")

        val sessionTags = storyCreationSessionTagRepository
            .findAllWithTagByCreationSessionId(request.simpleCreationId)
            .map { it.tag }
        val genreTags = sessionTags
            .filter { it.category == SimpleStoryTagCategory.GENRE }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val aiRequest = AiStoryCompileRequest(
            genreTags = genreTags.map { it.name },
            protagonistTags = sessionTags.filter { it.category == SimpleStoryTagCategory.PROTAGONIST }.map { it.name },
            supportingTags = sessionTags.filter { it.category == SimpleStoryTagCategory.SUPPORTING_CHARACTER }.map { it.name },
            selectedStoryline = selectedStoryline.exampleText,
            extraInfo = request.additionalInfos.joinToString(separator = "\n"),
        )

        val recorded = try {
            aiCallRecorder.record(
                AiCallContext(feature = AiCallFeature.STORY_COMPLETION),
                meta = { it.meta?.toAiCallMeta() },
            ) {
                storyAiClient.compileStory(aiRequest)
            }
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리 생성 요청에 실패했습니다.", exception)
        }
        val aiResponse = recorded.result

        val genre = genreTags.joinToString(separator = ", ") { it.name }.ifEmpty { null }

        return transactionTemplate.execute {
            val lockedSession = storyCreationSessionRepository.findByIdForUpdate(session.id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "간편 제작 진행 정보를 찾을 수 없습니다.")
            if (lockedSession.status == StoryCreationSessionStatus.STORY_CREATED) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
            }

            selectedStoryline.isSelected = true
            storyCreationExampleRepository.save(selectedStoryline)

            val story = storyRepository.save(
                Story(
                    userId = attributedUserId,
                    title = aiResponse.stories.title.take(STORY_TITLE_MAX_LENGTH),
                    oneLineIntro = aiResponse.stories.oneLineIntro.take(STORY_ONE_LINE_INTRO_MAX_LENGTH),
                    description = aiResponse.stories.description,
                    genre = genre,
                ),
            )
            storySettingRepository.save(
                StorySetting(
                    story = story,
                    worldSetting = aiResponse.storySettings.worldSetting,
                    characterSetting = aiResponse.storySettings.characterSetting,
                    userRoleSetting = aiResponse.storySettings.userRoleSetting,
                    ruleSetting = aiResponse.storySettings.ruleSetting,
                ),
            )
            val startSetting = storyStartSettingRepository.save(
                StoryStartSetting(
                    story = story,
                    name = aiResponse.storyStartSettings.name,
                    prologue = aiResponse.storyStartSettings.prologue,
                    startSituation = aiResponse.storyStartSettings.startSituation,
                ),
            )
            storySuggestedInputRepository.saveAll(
                aiResponse.storySuggestedInputs.mapIndexed { index, inputText ->
                    StorySuggestedInput(
                        startSetting = startSetting,
                        inputText = inputText,
                        inputOrder = (index + 1).toShort(),
                    )
                },
            )

            // 익명 세션을 로그인 사용자가 완료(claim)하면 세션 소유자도 그 사용자로 박는다 — 안 그러면 그 스토리의
            // 스토리라인 평가 소유권 검사(session.userId 기반)가 세션을 익명으로 보아 아무나 평가/취소할 수 있다(Codex PR #76 P2).
            lockedSession.userId = attributedUserId
            lockedSession.status = StoryCreationSessionStatus.STORY_CREATED
            lockedSession.storyId = story.id
            storyCreationSessionRepository.save(lockedSession)

            StoryCreationOutcome(
                response = SimpleStoryCreateResponse(
                    id = story.publicId.toString(),
                    title = story.title,
                    oneLineIntro = story.oneLineIntro,
                    description = story.description,
                    genres = genreTags.map { it.name },
                    startSetting = StoryStartSettingResponse(
                        name = aiResponse.storyStartSettings.name,
                        prologue = aiResponse.storyStartSettings.prologue,
                        startSituation = aiResponse.storyStartSettings.startSituation,
                    ),
                ),
                aiCallLogId = recorded.aiCallLogId,
            )
        } ?: throw IllegalStateException("Story creation transaction result is empty")
    }

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

    private fun List<StoryCreationTagDraft>.toAiStorylinesRequest(): AiStorylinesRequest =
        AiStorylinesRequest(
            genreTags = filter { it.category == SimpleStoryTagCategory.GENRE }.map { it.name },
            protagonistTags = filter { it.category == SimpleStoryTagCategory.PROTAGONIST }.map { it.name },
            supportingTags = filter { it.category == SimpleStoryTagCategory.SUPPORTING_CHARACTER }.map { it.name },
        )

    private fun StoryCreationTag.toTagResponse(): SimpleStoryTagResponse =
        SimpleStoryTagResponse(
            id = id,
            name = name,
            category = category,
        )

    private data class StoryCreationOutcome(
        val response: SimpleStoryCreateResponse,
        val aiCallLogId: Long,
    )

    private data class StoryCreationTagDraft(
        val category: SimpleStoryTagCategory,
        val name: String,
    ) {
        val key: Pair<SimpleStoryTagCategory, String>
            get() = category to name
    }

    private fun findOrCreateCustomTags(customTagDrafts: List<StoryCreationTagDraft>): List<StoryCreationTag> {
        if (customTagDrafts.isEmpty()) {
            return emptyList()
        }

        val existingTags = customTagDrafts
            .groupBy { it.category }
            .flatMap { (category, drafts) ->
                storyCreationTagRepository.findByTagSourceAndCategoryAndNameIn(
                    tagSource = StoryCreationTagSource.CUSTOM,
                    category = category,
                    names = drafts.map { it.name },
                )
            }
        val tagsByKey = existingTags.associateBy { it.category to it.name }.toMutableMap()
        val newTags = customTagDrafts
            .filterNot { tagsByKey.containsKey(it.key) }
            .map { tag ->
                StoryCreationTag(
                    category = tag.category,
                    name = tag.name,
                    tagSource = StoryCreationTagSource.CUSTOM,
                    sortOrder = 0,
                    isActive = true,
                )
            }

        storyCreationTagRepository.saveAll(newTags)
            .forEach { tag -> tagsByKey[tag.category to tag.name] = tag }

        return customTagDrafts.map { tag -> tagsByKey.getValue(tag.key) }
    }
}
