package com.knk.manyak.story.service

import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import com.knk.manyak.story.client.AiLorebookItem
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
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationStorylineRecommendedInfo
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryCreationStorylineRecommendedInfoRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationTagRepository
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryEndingRepository
import com.knk.manyak.story.repository.StoryLorebookRepository
import com.knk.manyak.story.repository.StoryMainEventRepository
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.story.repository.StorySettingRepository
import com.knk.manyak.story.repository.StoryStartSettingRepository
import com.knk.manyak.story.repository.StorySuggestedInputRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class SimpleStoryCreationService(
    private val storyCreationTagRepository: StoryCreationTagRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationSessionTagRepository: StoryCreationSessionTagRepository,
    private val storyCreationStorylineRepository: StoryCreationStorylineRepository,
    private val storyCreationStorylineRecommendedInfoRepository: StoryCreationStorylineRecommendedInfoRepository,
    private val storyRepository: StoryRepository,
    private val storySettingRepository: StorySettingRepository,
    private val storyStartSettingRepository: StoryStartSettingRepository,
    private val storySuggestedInputRepository: StorySuggestedInputRepository,
    private val lorebookRepository: LorebookRepository,
    private val storyLorebookRepository: StoryLorebookRepository,
    private val storyMainEventRepository: StoryMainEventRepository,
    private val storyEndingRepository: StoryEndingRepository,
    private val storyAiClient: StoryAiClient,
    private val structuredLogger: StructuredLogger,
    private val aiCallRecorder: AiCallRecorder,
    private val creditWalletService: CreditWalletService,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val suspensionGuard: SuspensionGuard,
    private val serverAnalytics: ServerAnalytics,
    // 간편 제작 1회 소모 크레딧(스펙 §4-3-7, KNK-477 확정: 20).
    @param:Value("\${manyak.credit.story-creation-cost:20}")
    private val storyCreationCost: Long,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private companion object {
        // AI 응답이 컬럼 길이를 초과해도 트랜잭션이 실패하지 않도록 방어적으로 자른다. (stories 컬럼 정의와 일치)
        const val STORY_TITLE_MAX_LENGTH = 100
        const val STORY_ONE_LINE_INTRO_MAX_LENGTH = 255

        // 주요 사건·엔딩 이름 컬럼(VARCHAR(100)) 초과를 방어적으로 자른다.
        const val STORY_MAIN_EVENT_NAME_MAX_LENGTH = 100
        const val STORY_ENDING_NAME_MAX_LENGTH = 100

        // 크레딧 원장 소모·환불 행의 ref_type(연관 리소스 종류). 소모는 STORY 리소스를 가리킨다(스펙 §4-3-7).
        const val STORY_CREDIT_REF_TYPE = "STORY"
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
        val guestDeviceId = guestTrialLimitService.reserveForGuestOrNull(
            userId,
            deviceId,
            GuestTrialLimitService.Counter.STORYLINE_GENERATION,
        )
        try {
            return doGenerateSimpleStorylines(request, userId)
        } catch (throwable: Throwable) {
            guestDeviceId?.let { guestTrialLimitService.restore(it, GuestTrialLimitService.Counter.STORYLINE_GENERATION) }
            throw throwable
        }
    }

    private fun doGenerateSimpleStorylines(
        request: GenerateSimpleStorylinesRequest,
        userId: Long?,
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
            // 스토리라인 생성 실패 분석 이벤트(스펙 §6-4-2-3). 세션 생성 전이라 creation_id는 아직 없다.
            serverAnalytics.storylineGenerationFailed(
                userId = userId,
                creationId = null,
                errorType = AnalyticsErrorType.fromThrowable(exception),
            )
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리라인 생성 요청에 실패했습니다.", exception)
        }

        val response = try {
            transactionTemplate.execute {
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

            val storylineResponses = storylines.map { storyline ->
                SimpleStorylineResponse(
                    id = storyline.id,
                    storyline = storyline.storylineText,
                    recommendedInfos = recommendedInfos[storyline.id].orEmpty().map { info ->
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
                storylines = storylineResponses,
            )
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

    fun createSimpleStory(
        request: CreateSimpleStoryRequest,
        userId: Long? = null,
        deviceId: String? = null,
    ): SimpleStoryCreateResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499).
        val startNanos = System.nanoTime()
        structuredLogger.event("story_create_requested", "creation_id" to request.simpleCreationId)
        try {
            val outcome = doCreateSimpleStory(request, userId, deviceId)
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

    private fun doCreateSimpleStory(request: CreateSimpleStoryRequest, userId: Long?, deviceId: String?): StoryCreationOutcome {
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

        val selectedStoryline = storyCreationStorylineRepository
            .findByIdAndCreationSessionId(request.storylineId, request.simpleCreationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 스토리라인을 찾을 수 없습니다.")

        val sessionTags = storyCreationSessionTagRepository
            .findAllWithTagByCreationSessionId(request.simpleCreationId)
            .map { it.tag }
        val genreTags = sessionTags
            .filter { it.category == SimpleStoryTagCategory.GENRE }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        // 스토리 장르로 로어북을 선별해 compile 요청에 세계관·용어 확장 재료로 싣는다(스펙 §4-3-6·§5-3-3).
        // 전달분은 저장 성공 시 story_lorebooks에 연결한다(compileAndPersist).
        val selectedLorebooks = selectLorebooksForGenres(genreTags)
        val aiRequest = AiStoryCompileRequest(
            genreTags = genreTags.map { it.name },
            protagonistTags = sessionTags.filter { it.category == SimpleStoryTagCategory.PROTAGONIST }.map { it.name },
            supportingTags = sessionTags.filter { it.category == SimpleStoryTagCategory.SUPPORTING_CHARACTER }.map { it.name },
            selectedStoryline = selectedStoryline.storylineText,
            additionalInfo = request.additionalInfos.joinToString(separator = "\n"),
            lorebooks = selectedLorebooks.map { AiLorebookItem(name = it.name, content = it.content) },
        )

        // 소모(스펙 §4-3-7): 회원만 compile 시작 전에 선차감한다. 게스트(attributedUserId == null)는 차감 대신
        // 디바이스 ID별 story_creation 체험 한도를 예약한다(한도 소진 시 402, AI 호출 전 거절).
        // 잔액 부족·한도 소진은 동기 402로 변환하고(SSE 없이 즉시 실패), 이후 compile/저장이 실패하면 전액 환불·카운터 복원한다.
        // refId는 compile 전에 확정돼 있는 세션 id(=simpleCreationId)를 쓴다(story.id는 저장 성공 후에야 생긴다).
        //
        // 환불 멱등 키는 이 호출(=차감 시도)마다 새로 만든다(chargeAttemptId). deduct는 멱등 키가 없어 시도마다 실제 차감되므로,
        // 첫 시도 실패 후 같은 simpleCreationId로 재시도(첫 시도가 STORY_CREATED에 못 이르러 허용됨)하면 두 번째 차감이 또 생긴다.
        // 세션 단위 고정 키를 쓰면 두 번째 환불이 첫 환불 키와 충돌해 미적립(rewarded=false)되어 크레딧이 유실된다(Codex P1).
        // 시도별 키면 각 시도의 차감·환불이 독립적으로 짝지어져 재시도에도 유실이 없다.
        val chargeAttemptId = UUID.randomUUID().toString()
        val guestDeviceId = guestTrialLimitService.reserveForGuestOrNull(
            attributedUserId,
            deviceId,
            GuestTrialLimitService.Counter.STORY_CREATION,
        )
        // 회원 소모 2단(스펙 §4-3-7 B13): 계정 귀속 체험 잔여가 있으면 먼저 무료로 소진하고, 없으면 크레딧을 선차감한다.
        val memberTrialCovered = attributedUserId != null &&
            guestTrialLimitService.reserveMember(attributedUserId, GuestTrialLimitService.Counter.STORY_CREATION)
        if (attributedUserId != null && !memberTrialCovered) {
            chargeStoryCreation(attributedUserId, refId = session.id)
        }

        return runWithRefundOnFailure(
            userId = attributedUserId,
            guestDeviceId = guestDeviceId,
            memberTrialCovered = memberTrialCovered,
            refId = session.id,
            chargeAttemptId = chargeAttemptId,
        ) {
            compileAndPersist(session, attributedUserId, selectedStoryline, genreTags, selectedLorebooks, aiRequest)
        }
    }

    /** 스토리 장르 태그와 일치하는 활성 로어북을 선별한다. 장르 태그가 없으면 빈 목록. */
    private fun selectLorebooksForGenres(genreTags: List<StoryCreationTag>): List<Lorebook> {
        val genres = genreTags.map { it.name }.distinct()
        if (genres.isEmpty()) {
            return emptyList()
        }
        return lorebookRepository.findByGenreInAndIsActiveTrueOrderByGenreAscSortOrderAscIdAsc(genres)
    }

    /** 회원 선차감. 잔액 부족([InsufficientCreditException])은 동기 402로 변환한다. */
    private fun chargeStoryCreation(userId: Long, refId: Long) {
        try {
            creditWalletService.deduct(
                userId = userId,
                amount = storyCreationCost,
                reason = CreditReason.STORY_CREATION,
                refType = STORY_CREDIT_REF_TYPE,
                refId = refId,
            )
        } catch (exception: InsufficientCreditException) {
            // 게스트 체험 한도(GUEST_TRIAL_LIMIT_EXCEEDED)와 같은 402지만 바디 code로 사유를 구분한다(KNK-524).
            throw CodedResponseStatusException(
                HttpStatus.PAYMENT_REQUIRED,
                ApiErrorCodes.INSUFFICIENT_CREDIT,
                "크레딧이 부족합니다.",
                exception,
            )
        }
    }

    /**
     * [block]이 성공하면 선차감·게스트 예약을 유지하고, 실패(예외)하면 회원은 전액 환불, 게스트는 예약한
     * 체험 한도 카운터를 복원한 뒤 원래 예외를 다시 던진다(회원·게스트는 배타적이라 [userId]·[guestDeviceId] 중 하나만 채워짐).
     *
     * 환불 멱등 키(`refund:story:{chargeAttemptId}`)는 차감 시도별로 유일하므로, 같은 세션을 재시도해 여러 번 차감돼도
     * 각 차감이 자기 시도의 환불과만 짝지어진다(재시도 시 환불 유실 방지). 재시도 안전은 시도별 키가, 중복 실행 방지는
     * 이 키의 유니크 제약이 함께 보장한다.
     */
    private fun <T> runWithRefundOnFailure(
        userId: Long?,
        guestDeviceId: String?,
        memberTrialCovered: Boolean,
        refId: Long,
        chargeAttemptId: String,
        block: () -> T,
    ): T {
        try {
            return block()
        } catch (throwable: Throwable) {
            if (memberTrialCovered) {
                // 체험 잔여로 무료 처리됐으면 크레딧 환불이 아니라 회원 체험 카운터를 되돌린다(스펙 §4-3-7 B13).
                userId?.let { guestTrialLimitService.restoreMember(it, GuestTrialLimitService.Counter.STORY_CREATION) }
            } else {
                userId?.let { refundStoryCreation(it, refId, chargeAttemptId) }
            }
            guestDeviceId?.let { guestTrialLimitService.restore(it, GuestTrialLimitService.Counter.STORY_CREATION) }
            throw throwable
        }
    }

    private fun refundStoryCreation(userId: Long, refId: Long, chargeAttemptId: String) {
        creditWalletService.reward(
            userId = userId,
            amount = storyCreationCost,
            reason = CreditReason.REFUND,
            idempotencyKey = "refund:story:$chargeAttemptId",
            refType = STORY_CREDIT_REF_TYPE,
            refId = refId,
        )
    }

    private fun compileAndPersist(
        session: StoryCreationSession,
        // finalize에서 이미 소유권을 반영해 확정한 귀속 사용자(익명 세션을 로그인 사용자가 claim한 경우 그 사용자). 게스트면 null.
        attributedUserId: Long?,
        selectedStoryline: StoryCreationStoryline,
        genreTags: List<StoryCreationTag>,
        // compile 요청에 실어 보낸 선별 로어북. 저장 성공 시 story_lorebooks에 연결한다(전달분과 저장분 일치).
        selectedLorebooks: List<Lorebook>,
        aiRequest: AiStoryCompileRequest,
    ): StoryCreationOutcome {
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
            storyCreationStorylineRepository.save(selectedStoryline)

            val story = storyRepository.save(
                Story(
                    userId = attributedUserId,
                    title = aiResponse.stories.title.take(STORY_TITLE_MAX_LENGTH),
                    oneLineIntro = aiResponse.stories.oneLineIntro.take(STORY_ONE_LINE_INTRO_MAX_LENGTH),
                    description = aiResponse.stories.description,
                    genre = genre,
                    // 제작 스토리 기본 공개 범위는 PRIVATE다(KNK-464 팀 결정). 공개는 제작 시 선택으로 전환한다.
                    visibility = StoryVisibility.PRIVATE,
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
            val savedSuggestedInputs = storySuggestedInputRepository.saveAll(
                aiResponse.storySuggestedInputs.mapIndexed { index, inputText ->
                    StorySuggestedInput(
                        startSetting = startSetting,
                        inputText = inputText,
                        inputOrder = (index + 1).toShort(),
                    )
                },
            ).map { it.inputText }

            // 전달한 로어북을 스토리에 연결한다(sort_order 1-based, ck_story_lorebooks_sort_order > 0).
            if (selectedLorebooks.isNotEmpty()) {
                storyLorebookRepository.saveAll(
                    selectedLorebooks.mapIndexed { index, lorebook ->
                        StoryLorebook(
                            story = story,
                            lorebook = lorebook,
                            sortOrder = (index + 1).toShort(),
                        )
                    },
                )
            }

            // 컴파일 산출물의 주요 사건(스토리 소유, sort_order 0-based)을 저작 경로와 같은 테이블에 저장한다.
            if (aiResponse.storyMainEvents.isNotEmpty()) {
                // 저장 이름(방어적 절단 후)이 스토리 안에서 유니크여야 이름 기반 완결·목표 매칭이 무모호하다.
                // 중복은 AI 응답의 결함이므로 400이 아니라 502(불완전 AI 응답)로 처리하고 저장을 롤백한다(엔딩과 동일).
                val mainEventNames = aiResponse.storyMainEvents.map { it.name.take(STORY_MAIN_EVENT_NAME_MAX_LENGTH) }
                if (mainEventNames.size != mainEventNames.toSet().size) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 컴파일 응답의 주요 사건 이름이 중복됩니다.")
                }
                storyMainEventRepository.saveAll(
                    aiResponse.storyMainEvents.mapIndexed { index, item ->
                        StoryMainEvent(
                            story = story,
                            name = mainEventNames[index],
                            description = item.description,
                            keySentence = item.keySentence,
                            sortOrder = index.toShort(),
                        )
                    },
                )
            }

            // 컴파일 산출물의 엔딩(시작 설정 스코프, sort_order 1-based, ck_story_endings_order > 0)을 저장한다.
            val savedEndings = if (aiResponse.storyEndings.isEmpty()) {
                emptyList()
            } else {
                // 저장 이름(방어적 절단 후)이 시작 설정 안에서 유니크여야 이름 기반 도달 매칭이 무모호하다(제작·수정과 동일 불변식).
                // 중복은 사용자 입력이 아니라 AI 응답의 결함이므로 400이 아니라 502(불완전 AI 응답)로 처리하고 저장을 롤백한다.
                val endingNames = aiResponse.storyEndings.map { it.name.take(STORY_ENDING_NAME_MAX_LENGTH) }
                if (endingNames.size != endingNames.toSet().size) {
                    throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 컴파일 응답의 엔딩 이름이 중복됩니다.")
                }
                storyEndingRepository.saveAll(
                    aiResponse.storyEndings.mapIndexed { index, item ->
                        StoryEnding(
                            startSetting = startSetting,
                            name = endingNames[index],
                            minTurns = item.minTurns,
                            achievementCondition = item.achievementCondition,
                            epilogue = item.epilogue,
                            sortOrder = (index + 1).toShort(),
                        )
                    },
                ).toList()
            }

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
                    // 간편 제작은 시작 설정 1개다(KNK-515 복수화). 추천 입력·엔딩을 그 시작 설정에 종속시킨다.
                    startSettings = listOf(
                        StoryStartSettingResponse(
                            id = startSetting.publicId.toString(),
                            name = startSetting.name,
                            prologue = startSetting.prologue,
                            startSituation = startSetting.startSituation,
                            suggestedInputs = savedSuggestedInputs,
                            endings = savedEndings.map { it.toEndingResponse() },
                        ),
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
