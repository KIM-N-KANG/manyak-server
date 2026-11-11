package com.knk.manyak.story.service

import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.ApiErrorDetail
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import com.knk.manyak.story.client.AiCharacter
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.client.StoryContentRejectedException
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.ParentCreationLink
import com.knk.manyak.story.dto.ParentLinkError
import com.knk.manyak.story.dto.SimpleStoryCharacterGender
import com.knk.manyak.story.dto.SimpleStoryCharacterRequest
import com.knk.manyak.story.dto.SimpleStoryRecommendedInfoResponse
import com.knk.manyak.story.dto.SimpleStorySelectedCharacterResponse
import com.knk.manyak.story.dto.SimpleStorySelectedTagsResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.SimpleStoryTagListItemResponse
import com.knk.manyak.story.dto.SimpleStoryTagResponse
import com.knk.manyak.story.dto.SimpleStorylineResponse
import com.knk.manyak.story.dto.StoryCreationCharacterRole
import com.knk.manyak.story.dto.StoryCreationSessionStatus
import com.knk.manyak.story.dto.StoryCreationStage
import com.knk.manyak.story.dto.StoryCreationTagSource
import com.knk.manyak.story.entity.StoryCreationCharacter
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationStorylineRecommendedInfo
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.hasSameChainOwnerAs
import com.knk.manyak.story.repository.StoryCreationCharacterRepository
import com.knk.manyak.story.repository.StoryCreationRequestRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRecommendedInfoRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry

/**
 * 간편 제작 스토리라인 단계(스펙 4-1 간편 제작 절). 태그 목록을 주고, 장르와 인물 입력으로 스토리라인 후보를 만들어 저장한다
 * (KNK-2056에서 SimpleStoryCreationService를 나눔). 요청 기록과 멱등은 [StoryCreationRequestRecorder]가 맡는다.
 */
@Service
class SimpleStorylineService(
    private val storyCreationTagRepository: StoryCreationTagRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationCharacterRepository: StoryCreationCharacterRepository,
    private val storyCreationSessionTagRepository: StoryCreationSessionTagRepository,
    private val storyCreationStorylineRepository: StoryCreationStorylineRepository,
    private val storyCreationStorylineRecommendedInfoRepository: StoryCreationStorylineRecommendedInfoRepository,
    private val storyAiClient: StoryAiClient,
    private val meterRegistry: MeterRegistry,
    private val aiCallRecorder: AiCallRecorder,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val serverAnalytics: ServerAnalytics,
    private val storyCreationRequestRecorder: StoryCreationRequestRecorder,
    private val storyCreationRequestRepository: StoryCreationRequestRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    // 커스텀 태그 마스터 행 삽입 전용. 동시 생성 유니크 충돌을 진행 중인 저장 트랜잭션과 분리해 흡수한다(KNK-717).
    private val newTagTransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional(readOnly = true)
    fun getSimpleStoryTags(): List<SimpleStoryTagListItemResponse> =
        storyCreationTagRepository
            .findByTagSourceAndIsActiveTrueOrderByCategoryAscSortOrderAscIdAsc(StoryCreationTagSource.PREDEFINED)
            .filter { it.category != SimpleStoryTagCategory.GENRE || it.featuredOrder != null }
            .sortedWith(compareBy<StoryCreationTag> { it.category.name }
                .thenBy { if (it.category == SimpleStoryTagCategory.GENRE) it.featuredOrder else it.sortOrder }
                .thenBy { it.id })
            .map { tag ->
                SimpleStoryTagListItemResponse(
                    id = tag.id,
                    name = tag.name,
                    category = tag.category,
                )
            }

    /**
     * 스토리라인 생성(스펙 §4-3-7): 회원은 무료다. 게스트([userId] null)는 디바이스 ID별
     * `storyline_generation` 카운터(생성·재생성 합산)를 AI 호출 전에 예약하고, 한도 소진이면 402를 반환한다.
     * 3개 생성이 성공하지 못하면(AI 실패·저장 실패) 예약한 카운터를 복원한다.
     */
    fun generateSimpleStorylines(
        request: GenerateSimpleStorylinesRequest,
        userId: Long? = null,
        deviceId: String? = null,
    ): GenerateSimpleStorylinesResponse {
        // 부모 링크 검증은 요청 행 삽입(recordOrRun 안의 별도 트랜잭션)보다 먼저 끝나야 한다 — 결과 3값이 그 삽입에 실린다.
        val parentLink = resolveParentLink(request, userId, storyCreationRequestRecorder.deviceIdHashOrNull(deviceId))
        // 체인은 위에서 검증한 값이 아니라 **요청 행에 실제로 기록된 값**(recordedParentLink)을 쓴다 — 재실행이면 최초 삽입 때
        // 확정된 저장값이라, 재시도 본문이 부모를 빼거나 바꿔도 헤더가 parent_request_id와 어긋나지 않는다(Codex P2).
        val generate: (Boolean, Boolean, ParentCreationLink?) -> GenerateSimpleStorylinesResponse =
            { isReclaim, isIncompatibleReplayFallback, recordedParentLink ->
                // 회수 재구성(KNK-848): 세션은 커밋됐지만 요청 행 COMPLETED 마킹을 잃어 aged PENDING으로 남은 창에서, 같은 requestId
                // 회수가 AI·게스트 한도를 다시 소모하지 않도록 저장된 세션을 재구성해 반환한다(완성 경로 reconcileCreatedSession 대응).
                // 호환 불가 replay fallback은 옛 결과를 못 읽어 의도적으로 재생성하는 별개 경로라 재구성하지 않는다(그 세션 재사용은 옛 동작을 깬다).
                // 세션이 없으면(진짜 실패의 aged PENDING) null → 종전대로 생성한다.
                val reclaimed = if (isReclaim && !isIncompatibleReplayFallback) reconcileGeneratedStorylines(request) else null
                reclaimed ?: run {
                    // AI 호출에 진입했는지. 실패 outcome을 rejected(밀리초 거부)와 failure(실제 생성 실패)로 가르는 기준이다
                    // — 완성 경로와 같은 이유로 HTTP 상태만으로는 가를 수 없다([storylineOutcomeOf]).
                    var aiCallStarted = false
                    // 게스트 한도 예약(402)까지 감싸야 한다 — 그 거부가 이 지표를 만든 이유(양쪽 관측 사각지대)이기 때문이다.
                    try {
                        // 호환되지 않는 옛 COMPLETED 응답의 fallback은 최초 성공 때 이미 예약한 한도를 다시 소모하지 않는다.
                        val guestDeviceId = if (isIncompatibleReplayFallback) {
                            null
                        } else {
                            guestTrialLimitService.reserveForGuestOrNull(
                                userId,
                                deviceId,
                                GuestTrialLimitService.Counter.STORYLINE_GENERATION,
                            )
                        }
                        val response = try {
                            doGenerateSimpleStorylines(request, userId, recordedParentLink) { aiCallStarted = true }
                        } catch (throwable: Throwable) {
                            guestDeviceId?.let { guestTrialLimitService.restore(it, GuestTrialLimitService.Counter.STORYLINE_GENERATION) }
                            throw throwable
                        }
                        recordStorylineResult(OUTCOME_SUCCESS)
                        response
                    } catch (throwable: Throwable) {
                        recordStorylineResult(storylineOutcomeOf(throwable, aiCallStarted))
                        throw throwable
                    }
                }
            }
        return storyCreationRequestRecorder.recordOrRun(
            request.requestId,
            StoryCreationStage.STORYLINE_GENERATION,
            userId,
            deviceId,
            GenerateSimpleStorylinesResponse::class.java,
            parentLink,
            block = generate,
            validateNewRequest = {
                if (request.customGenreTags.isNotEmpty()) {
                    throw CodedResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        ApiErrorCodes.CUSTOM_GENRE_NOT_ALLOWED,
                        "요청 값이 올바르지 않습니다.",
                        details = listOf(ApiErrorDetail("customGenreTags", "제공 장르에서 선택해 주세요.")),
                    )
                }
            },
        )
    }

    /**
     * 재생성 체인의 부모 링크를 검증한다(KNK-755). 부모를 안 보냈으면 null(체인 시도 자체가 없었다).
     *
     * 실패해도 400으로 거부하지 않는다 — 이 레포 원칙대로 관측이 비즈니스를 막지 않는다. 대신 시도값과 사유를 남겨,
     * "최초 생성"과 "재생성인데 연결 실패"를 DB에서 구분한다.
     *
     * 자기참조를 존재 확인보다 **먼저** 판정한다: DB 조회 없이 결정되고, 이 시점엔 자기 요청 행이 아직 삽입 전이라
     * 존재 확인을 먼저 돌리면 자기참조가 NOT_FOUND로 오진되기 때문이다(진짜 사유가 가려진다).
     */
    private fun resolveParentLink(
        request: GenerateSimpleStorylinesRequest,
        userId: Long?,
        deviceIdHash: String?,
    ): ParentCreationLink? {
        val attempted = request.parentCreationId ?: return null
        val error = if (attempted == request.requestId) {
            ParentLinkError.SELF_REFERENCE
        } else {
            val parent = storyCreationRequestRepository.findByRequestId(attempted)
            when {
                parent == null || parent.stage != StoryCreationStage.STORYLINE_GENERATION -> ParentLinkError.NOT_FOUND
                !parent.hasSameChainOwnerAs(userId, deviceIdHash) -> ParentLinkError.OWNER_MISMATCH
                else -> null
            }
        }
        return ParentCreationLink(attempted, error)
    }

    /**
     * AI trace 여정을 묶는 연결 식별자(KNK-751)를 스토리라인 호출용으로 만든다.
     * creation_id는 이 요청의 request_id다 — AI 호출 전에 커밋되므로 실패해도 남고, 재시도에도 같은 값이다.
     *
     * 부모 creation_id는 **검증을 통과한 값만** 나간다(KNK-755) — 미검증 pass-through는 존재하지 않거나 남의 여정을
     * 가리키는 체인을 AI가 신뢰하게 만든다. 재생성 여부는 서버가 알 수 없어 프론트가 준 값을 그대로 전달한다.
     */
    private fun storylineTraceLink(request: GenerateSimpleStorylinesRequest, parentLink: ParentCreationLink?) = AiTraceLink(
        creationId = request.requestId,
        parentCreationId = parentLink?.validatedParentRequestId,
        isRegenerated = request.isRegenerated,
    )

    private fun doGenerateSimpleStorylines(
        request: GenerateSimpleStorylinesRequest,
        userId: Long?,
        parentLink: ParentCreationLink?,
        // AI 호출 진입 직전에 부른다. 호출부가 실패 outcome을 rejected/failure로 가르는 데 쓴다([storylineOutcomeOf]).
        onAiCallStarted: () -> Unit,
    ): GenerateSimpleStorylinesResponse {
        val genreTags = findSelectedPredefinedTags(request.genreTagIds, SimpleStoryTagCategory.GENRE)
        val characterDrafts = listOf(
            request.protagonist.toCharacterDraft(StoryCreationCharacterRole.PROTAGONIST, sortOrder = 1),
        ) + request.supportingCharacters.mapIndexed { index, character ->
            character.toCharacterDraft(StoryCreationCharacterRole.SUPPORTING_CHARACTER, sortOrder = index + 1)
        }
        // 장르 직접 입력(KNK-859). 인물 특징과 같은 정규화 키 규칙으로 다루되 분류만 GENRE다.
        val customGenreDrafts = request.customGenreTags
            .map { StoryCreationTagDraft(category = SimpleStoryTagCategory.GENRE, name = it.trim()) }
            .distinctBy { it.key }
        val customTagDrafts = (customGenreDrafts + characterDrafts.flatMap { it.customTags }).distinctBy { it.key }

        // AI 인물 단위 계약(KNK-846): 장르 + 주인공/주변 인물별 {name, gender, features}. 아직 저장 전이라 요청 draft로 조립한다.
        val aiRequest = AiStorylinesRequest(
            // 제공 장르를 앞에 둬, 정규화 키가 겹치는 직접 입력 장르는 제공 표기를 남긴다(인물 특징과 같은 규칙).
            genreTags = (genreTags.map { it.name } + customGenreDrafts.map { it.name })
                .distinctBy { StoryCreationTag.normalize(it) },
            protagonist = characterDrafts.first { it.role == StoryCreationCharacterRole.PROTAGONIST }.toAiCharacter(),
            supportingCharacters = characterDrafts
                .filter { it.role == StoryCreationCharacterRole.SUPPORTING_CHARACTER }
                .map { it.toAiCharacter() },
        )

        // AI 진입 신호. 이 지점을 지난 뒤의 실패는 AI 내용 거절을 제외하고 실제 생성 실패다([storylineOutcomeOf]).
        onAiCallStarted()
        val recorded = try {
            aiCallRecorder.record(
                AiCallContext(feature = AiCallFeature.STORYLINE_GENERATION),
                meta = { it.meta?.toAiCallMeta() },
            ) {
                storyAiClient.createStorylines(aiRequest, storylineTraceLink(request, parentLink))
            }
        } catch (exception: Exception) {
            // 스토리라인 생성 실패 분석 이벤트(스펙 §6-4-2-3). 세션 생성 전이라 creation_id는 아직 없다.
            serverAnalytics.storylineGenerationFailed(
                userId = userId,
                creationId = null,
                errorType = AnalyticsErrorType.fromThrowable(exception),
            )
            if (exception is StoryContentRejectedException) {
                throw CodedResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCodes.CONTENT_REJECTED, requireNotNull(exception.message), exception,
                )
            }
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리라인 생성 요청에 실패했습니다.", exception)
        }

        val aiResponse = recorded.result

        val response = try {
            transactionTemplate.execute {
            val customTagsByKey = resolveCustomTags(customTagDrafts).associateBy { it.category to it.normalizedName }
            val creationSession = storyCreationSessionRepository.save(
                StoryCreationSession(
                    userId = userId,
                    status = StoryCreationSessionStatus.STORYLINES_GENERATED,
                    // 이 세션을 만든 스토리라인 요청의 request_id(KNK-751). 이후 컴파일·채팅이 같은 creation_id를 이어 쓴다.
                    storylineRequestId = request.requestId,
                    storylineAiCallLogId = recorded.aiCallLogId,
                ),
            )
            val characters = storyCreationCharacterRepository.saveAll(
                characterDrafts.map { character ->
                    StoryCreationCharacter(
                        creationSession = creationSession,
                        role = character.role,
                        name = character.name,
                        gender = character.gender,
                        sortOrder = character.sortOrder.toShort(),
                    )
                },
            )
            val featureTagsByCharacter = characterDrafts.zip(characters).associate { (draft, character) ->
                character to (
                    draft.predefinedTags + draft.customTags.map { customTagsByKey.getValue(it.key) }
                    ).distinctBy { it.id }
            }
            // 사전 정의 장르 → 직접 입력 장르 순. 세션 태그 저장 순서(st.id)가 곧 회수 재구성 응답 순서다(KNK-848).
            val sessionGenreTags = (
                genreTags + customGenreDrafts.map { customTagsByKey.getValue(it.key) }
                ).distinctBy { it.id }
            storyCreationSessionTagRepository.saveAll(
                sessionGenreTags.map { tag ->
                    StoryCreationSessionTag(
                        creationSession = creationSession,
                        tag = tag,
                    )
                } + featureTagsByCharacter.flatMap { (character, tags) ->
                    tags.map { tag ->
                        StoryCreationSessionTag(
                            creationSession = creationSession,
                            tag = tag,
                            character = character,
                        )
                    }
                },
            )

            val storylines = storyCreationStorylineRepository.saveAll(
                aiResponse.stories.mapIndexed { index, story ->
                    StoryCreationStoryline(
                        creationSession = creationSession,
                        storylineText = story.storyline,
                        storylineOrder = (index + 1).toShort(),
                    )
                },
            )
            val recommendedInfos = storyCreationStorylineRecommendedInfoRepository.saveAll(
                storylines.zip(aiResponse.stories).flatMap { (storyline, story) ->
                    story.recommendedInfos.mapIndexed { infoIndex, info ->
                        StoryCreationStorylineRecommendedInfo(
                            storyline = storyline,
                            infoText = info,
                            infoOrder = (infoIndex + 1).toShort(),
                        )
                    }
                }
            ).groupBy { info -> info.storyline.id }

            buildStorylinesResponse(creationSession, sessionGenreTags, characters, featureTagsByCharacter, storylines, recommendedInfos)
            } ?: throw IllegalStateException("Storyline creation transaction result is empty")
        } catch (exception: Exception) {
            // AI는 성공했으나 태그·세션·스토리라인 저장이 실패하면 실패 이벤트를 남긴다(Codex P2 — 저장 실패가 생성 퍼널에서 누락되지 않도록).
            serverAnalytics.storylineGenerationFailed(
                userId = userId,
                creationId = null,
                errorType = AnalyticsErrorType.fromThrowable(exception),
            )
            throw exception
        }
        // 스토리라인 생성 성공 분석 이벤트(스펙 §6-4-2-3). 세션 저장으로 확정된 creation_id를 싣는다.
        serverAnalytics.storylineGenerationSucceeded(userId = userId, creationId = response.simpleCreationId)
        return response
    }

    /**
     * 저장된 세션·인물·태그·스토리라인·추천 정보로 스토리라인 생성 응답을 조립한다.
     * 최초 생성(트랜잭션 안의 방금 저장분)과 회수 재구성([reconcileGeneratedStorylines])이 같은 코드로 응답을 만들게 공유한다.
     * [characters]는 주인공이 첫 원소여야 한다(생성 경로의 draft 순서·회수 경로의 role 오름차순 조회가 모두 그러함).
     */
    private fun buildStorylinesResponse(
        session: StoryCreationSession,
        genreTags: List<StoryCreationTag>,
        characters: List<StoryCreationCharacter>,
        featureTagsByCharacter: Map<StoryCreationCharacter, List<StoryCreationTag>>,
        storylines: List<StoryCreationStoryline>,
        recommendedInfosByStoryline: Map<Long, List<StoryCreationStorylineRecommendedInfo>>,
    ): GenerateSimpleStorylinesResponse =
        GenerateSimpleStorylinesResponse(
            simpleCreationId = session.id,
            selectedTags = SimpleStorySelectedTagsResponse(
                genreTags = genreTags.distinctBy { it.id }.map { it.toTagResponse() },
                protagonist = characters.first().toSelectedCharacterResponse(featureTagsByCharacter),
                supportingCharacters = characters.drop(1).map { it.toSelectedCharacterResponse(featureTagsByCharacter) },
            ),
            storylines = storylines.map { storyline ->
                SimpleStorylineResponse(
                    id = storyline.id,
                    storyline = storyline.storylineText,
                    recommendedInfos = recommendedInfosByStoryline[storyline.id].orEmpty().map { info ->
                        SimpleStoryRecommendedInfoResponse(id = info.id, text = info.infoText)
                    },
                )
            },
        )

    /**
     * 스토리라인 회수(reclaim) 재구성(KNK-848). 세션 저장 트랜잭션은 커밋됐지만 요청 행의 COMPLETED 마킹 커밋 전에 프로세스가
     * 죽어 aged PENDING으로 남은 좁은 창에서, 같은 requestId 회수가 AI·게스트 한도를 다시 소모하지 않도록 저장된 세션으로
     * 응답을 재구성한다(스토리 완성 경로의 [SimpleStoryCompletionWriter.reconcileCreatedSession]에 대응). 소유는 [StoryCreationRequestRecorder]가 isReclaim
     * 판정 전에 이미 검증했고, 여기서는 `storyline_request_id == requestId` 바인딩(이 조회 자체)으로 남의 세션 재구성을 막는다.
     * 이 requestId로 만든 세션이 없으면(진짜 실패의 aged PENDING) null을 반환해 정상 생성으로 폴백한다.
     */
    private fun reconcileGeneratedStorylines(request: GenerateSimpleStorylinesRequest): GenerateSimpleStorylinesResponse? {
        val session = storyCreationSessionRepository.findFirstByStorylineRequestIdOrderByIdAsc(request.requestId)
            ?: return null
        val characters = storyCreationCharacterRepository
            .findAllByCreationSessionIdOrderByRoleAscSortOrderAscIdAsc(session.id)
        val storylines = storyCreationStorylineRepository
            .findByCreationSessionIdOrderByStorylineOrderAscIdAsc(session.id)
        // 세션 저장은 원자적이라 커밋된 세션엔 인물·스토리라인이 함께 있다. 방어적으로 비정상 상태면 재구성하지 않고 정상 생성으로 폴백한다.
        if (characters.isEmpty() || storylines.isEmpty()) {
            return null
        }

        val sessionTagRows = storyCreationSessionTagRepository.findAllWithTagByCreationSessionId(session.id)
        // 장르는 세션 스코프(character_id NULL), 특징은 character_id별. 최초 생성의 저장 순서(태그 id 오름차순)를 그대로 재현한다.
        val genreTags = sessionTagRows
            .filter { it.character == null && it.tag.category == SimpleStoryTagCategory.GENRE }
            .map { it.tag }
        val featureTagsByCharacterId = sessionTagRows
            .filter { it.character != null }
            .groupBy { it.character!!.id }
            .mapValues { (_, rows) -> rows.map { it.tag } }
        val featureTagsByCharacter = characters.associateWith { featureTagsByCharacterId[it.id].orEmpty() }

        val recommendedInfosByStoryline = storyCreationStorylineRecommendedInfoRepository
            .findByStorylineIdInOrderByStorylineIdAscInfoOrderAsc(storylines.map { it.id })
            .groupBy { it.storyline.id }

        return buildStorylinesResponse(session, genreTags, characters, featureTagsByCharacter, storylines, recommendedInfosByStoryline)
    }

    /**
     * 스토리라인 생성 결과 분포를 `manyak.storyline.creation.result`로 집계한다(KNK-801).
     *
     * outcome 태그 값은 완성 타이머([recordCreationDuration])와 같은 3값이다:
     *   - `success`  : 스토리라인 생성·저장까지 끝난 호출
     *   - `failure`  : AI 호출에 진입한 뒤 깨진 호출 — AI 실패(502)·타임아웃·저장 실패
     *   - `rejected` : AI 내용 거절 또는 AI 호출 **이전에** 거부된 4xx — 게스트 한도 소진(402)·사용할 수 없는 태그 ID(400) 등
     *
     * **Timer가 아니라 Counter인 이유**: 스토리라인 생성은 AI 호출 1회가 유스케이스의 거의 전부라 소요가
     * `manyak.ai.call.duration{feature="storyline_generation"}`과 사실상 겹친다. 반면 결과 분포는 그쪽에 없다 —
     * 특히 **게스트 한도 402는 AI 호출 전에 끊기므로 AI 타이머에도 Langfuse trace에도 남지 않는다**(양쪽 관측 사각지대).
     * 히스토그램 버킷이 없어 시계열도 outcome 3개로 끝난다.
     *
     * 멱등 재요청([recordOrRun]의 COMPLETED replay)은 콜백 자체가 실행되지 않아 자연히 빠진다. 완성 경로와 달리
     * 스토리라인에는 회수 재실행 재구성 개념이 없어 별도 제외 판별이 필요 없다.
     * 태그는 outcome 하나뿐이다(위 3값 — 고유값을 넣지 않는다는 카디널리티 규칙).
     */
    private fun recordStorylineResult(outcome: String) {
        // 메트릭 기록 실패가 성공한 생성을 500으로 만들거나 실패 경로에서 원래 예외를 가리지 않도록 격리한다.
        runCatching {
            Counter.builder("manyak.storyline.creation.result")
                .description("간편 제작 스토리라인 생성 결과 분포")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment()
        }
    }

    /**
     * 스토리라인 생성 실패를 outcome 태그로 가른다([recordStorylineResult]의 rejected/failure 정의).
     *
     * 완성 경로([creationOutcomeOf])와 같은 이유로 **HTTP 상태만으로는 가를 수 없다** — AI 호출 이후 단계(저장)에서
     * 나는 4xx를 밀리초 거부로 오분류하면, 수십 초를 쓴 실패가 rejected로 새어 실패율을 낮춰 보이게 한다.
     * AI 내용 거절만 진입 뒤에도 rejected이고, 그 외에는 AI 진입 여부([aiCallStarted])를 먼저 본다.
     *
     * 여기서 `Throwable`을 받는 것은 호출부가 `Throwable`을 잡기 때문이다(게스트 한도 복원이 Error에도 돌아야 한다).
     */
    private fun storylineOutcomeOf(throwable: Throwable, aiCallStarted: Boolean): String = when {
        throwable is CodedResponseStatusException && throwable.errorCode == ApiErrorCodes.CONTENT_REJECTED -> OUTCOME_REJECTED
        aiCallStarted -> OUTCOME_FAILURE
        throwable is ResponseStatusException && throwable.statusCode.is4xxClientError -> OUTCOME_REJECTED
        else -> OUTCOME_FAILURE
    }

    private fun findSelectedPredefinedTags(
        tagIds: List<Long>,
        expectedCategory: SimpleStoryTagCategory,
    ): List<StoryCreationTag> {
        val distinctTagIds = tagIds.distinct()
        if (distinctTagIds.isEmpty()) {
            return emptyList()
        }

        val tagsById = storyCreationTagRepository
            .findByIdInAndTagSourceAndIsActiveTrue(distinctTagIds, StoryCreationTagSource.PREDEFINED)
            .filter { it.category == expectedCategory }
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

    /** 스토리라인 경로: 저장 전 draft를 인물 단위 AI 입력으로 변환한다. 제공 태그를 먼저 두어 정규화 키가 겹치는 직접 추가 태그는 제공 표기를 남긴다. */
    private fun StoryCreationCharacterDraft.toAiCharacter(): AiCharacter =
        AiCharacter(
            name = name,
            gender = gender?.name,
            features = (predefinedTags.map { it.name } + customTags.map { it.name })
                .distinctBy { StoryCreationTag.normalize(it) },
        )

    private fun StoryCreationTag.toTagResponse(): SimpleStoryTagResponse =
        SimpleStoryTagResponse(
            id = id,
            name = name,
            category = category,
        )

    private fun SimpleStoryCharacterRequest.toCharacterDraft(
        role: StoryCreationCharacterRole,
        sortOrder: Int,
    ): StoryCreationCharacterDraft {
        val category = when (role) {
            StoryCreationCharacterRole.PROTAGONIST -> SimpleStoryTagCategory.PROTAGONIST
            StoryCreationCharacterRole.SUPPORTING_CHARACTER -> SimpleStoryTagCategory.SUPPORTING_CHARACTER
        }
        return StoryCreationCharacterDraft(
            role = role,
            name = cleanedName(),
            gender = gender,
            sortOrder = sortOrder,
            predefinedTags = findSelectedPredefinedTags(featureTagIds, category),
            customTags = customTags.map { name ->
                StoryCreationTagDraft(category = category, name = name.trim())
            }.distinctBy { it.key },
        )
    }

    private fun StoryCreationCharacter.toSelectedCharacterResponse(
        featureTagsByCharacter: Map<StoryCreationCharacter, List<StoryCreationTag>>,
    ): SimpleStorySelectedCharacterResponse =
        SimpleStorySelectedCharacterResponse(
            name = name,
            gender = gender,
            features = featureTagsByCharacter.getValue(this).map { it.toTagResponse() },
        )

    private data class StoryCreationTagDraft(
        val category: SimpleStoryTagCategory,
        val name: String,
    ) {
        // 태그 동일성은 표시명이 아니라 정규화 키로 판정한다(KNK-717, 스펙 §4-3-2).
        val key: Pair<SimpleStoryTagCategory, String>
            get() = category to StoryCreationTag.normalize(name)
    }

    private data class StoryCreationCharacterDraft(
        val role: StoryCreationCharacterRole,
        val name: String?,
        val gender: SimpleStoryCharacterGender?,
        val sortOrder: Int,
        val predefinedTags: List<StoryCreationTag>,
        val customTags: List<StoryCreationTagDraft>,
    )

    /**
     * 직접 추가 태그를 정규화 키로 해석한다(KNK-717, 스펙 §4-3-2). 같은 카테고리에 정규화 키가 같은
     * 사전 정의 태그가 있으면 `CUSTOM` 행을 만들지 않고 그 행으로 연결하므로, 반환 목록에 `PREDEFINED`가 섞일 수 있다.
     */
    private fun resolveCustomTags(customTagDrafts: List<StoryCreationTagDraft>): List<StoryCreationTag> {
        if (customTagDrafts.isEmpty()) {
            return emptyList()
        }

        val tagsByKey = customTagDrafts
            .groupBy { it.category }
            .flatMap { (category, drafts) ->
                storyCreationTagRepository.findByCategoryAndNormalizedNameIn(
                    category = category,
                    normalizedNames = drafts.map { it.key.second },
                )
            }
            .groupBy { it.category to it.normalizedName }
            .mapValues { (_, tags) ->
                tags.firstOrNull { it.tagSource == StoryCreationTagSource.PREDEFINED } ?: tags.first()
            }
            .toMutableMap()

        customTagDrafts
            .filterNot { tagsByKey.containsKey(it.key) }
            .forEach { draft -> tagsByKey[draft.key] = createCustomTag(draft) }

        return customTagDrafts.map { draft -> tagsByKey.getValue(draft.key) }
    }

    /**
     * 커스텀 태그 1행을 만든다. 같은 정규화 키를 동시 요청이 먼저 만들면 유니크 충돌을 재조회로 흡수한다.
     * 별도 트랜잭션이라 충돌이 진행 중인 저장 트랜잭션을 말아올리지 않는다(대신 바깥이 실패해도 태그 행은 남는다 — 마스터 행이라 무해).
     */
    private fun createCustomTag(draft: StoryCreationTagDraft): StoryCreationTag {
        val tag = StoryCreationTag(
            category = draft.category,
            name = draft.name,
            tagSource = StoryCreationTagSource.CUSTOM,
        )
        return try {
            newTagTransactionTemplate.execute { storyCreationTagRepository.saveAndFlush(tag) }!!
        } catch (exception: DataIntegrityViolationException) {
            storyCreationTagRepository
                .findByCategoryAndNormalizedNameIn(draft.category, listOf(tag.normalizedName))
                .firstOrNull { it.tagSource == StoryCreationTagSource.CUSTOM }
                ?: throw exception
        }
    }
}
