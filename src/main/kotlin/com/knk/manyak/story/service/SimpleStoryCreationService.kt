package com.knk.manyak.story.service

import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.DeviceIdHasher
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.security.SuspensionGuard
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import com.knk.manyak.image.service.CharacterImageStorage
import com.knk.manyak.story.client.AiCharacter
import com.knk.manyak.story.client.AiCharacterAppearance
import com.knk.manyak.story.client.AiCharacterImage
import com.knk.manyak.story.client.AiLorebookItem
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.AiStorylinesRequest
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesRequest
import com.knk.manyak.story.dto.GenerateSimpleStorylinesResponse
import com.knk.manyak.story.dto.SimpleStoryCharacterGender
import com.knk.manyak.story.dto.SimpleStoryCharacterRequest
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryRecommendedInfoResponse
import com.knk.manyak.story.dto.SimpleStorySelectedCharacterResponse
import com.knk.manyak.story.dto.SimpleStorySelectedTagsResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.SimpleStoryTagListItemResponse
import com.knk.manyak.story.dto.SimpleStoryTagResponse
import com.knk.manyak.story.dto.SimpleStorylineResponse
import com.knk.manyak.story.dto.StoryCreationRequestStatusResponse
import com.knk.manyak.story.dto.StoryStartSettingResponse
import com.knk.manyak.story.dto.toEndingResponse
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.ParentCreationLink
import com.knk.manyak.story.entity.ParentLinkError
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.entity.StoryCharacter
import com.knk.manyak.story.entity.StoryCreationCharacter
import com.knk.manyak.story.entity.StoryCreationCharacterRole
import com.knk.manyak.story.entity.StoryCreationRequestStatus
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationStorylineRecommendedInfo
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationSessionStatus
import com.knk.manyak.story.entity.StoryCreationSessionTag
import com.knk.manyak.story.entity.StoryCreationStage
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.entity.StoryCreationTagSource
import com.knk.manyak.story.entity.hasSameChainOwnerAs
import com.knk.manyak.story.entity.isOwnedBy
import com.knk.manyak.story.entity.StoryEnding
import com.knk.manyak.story.entity.StoryLorebook
import com.knk.manyak.story.entity.StoryMainEvent
import com.knk.manyak.story.entity.StorySetting
import com.knk.manyak.story.entity.StoryStartSetting
import com.knk.manyak.story.entity.StorySuggestedInput
import com.knk.manyak.story.entity.StoryVisibility
import com.knk.manyak.story.repository.StoryCharacterRepository
import com.knk.manyak.story.repository.StoryCreationCharacterRepository
import com.knk.manyak.story.repository.StoryCreationRequestRepository
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
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class SimpleStoryCreationService(
    private val storyCreationTagRepository: StoryCreationTagRepository,
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationCharacterRepository: StoryCreationCharacterRepository,
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
    private val storyCharacterRepository: StoryCharacterRepository,
    private val characterImageStorage: CharacterImageStorage,
    private val storyAiClient: StoryAiClient,
    private val structuredLogger: StructuredLogger,
    private val meterRegistry: MeterRegistry,
    private val aiCallRecorder: AiCallRecorder,
    private val creditWalletService: CreditWalletService,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val suspensionGuard: SuspensionGuard,
    private val serverAnalytics: ServerAnalytics,
    private val storyThumbnailLinker: StoryThumbnailLinker,
    private val storyCreationRequestRecorder: StoryCreationRequestRecorder,
    private val storyCreationRequestRepository: StoryCreationRequestRepository,
    private val objectMapper: ObjectMapper,
    private val deviceIdHasher: DeviceIdHasher,
    // 간편 제작 1회 소모 크레딧(스펙 §4-3-7, KNK-477 확정: 20).
    @param:Value("\${manyak.credit.story-creation-cost:20}")
    private val storyCreationCost: Long,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    // 커스텀 태그 마스터 행 삽입 전용. 동시 생성 유니크 충돌을 진행 중인 저장 트랜잭션과 분리해 흡수한다(KNK-717).
    private val newTagTransactionTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private companion object {
        // AI 응답이 컬럼 길이를 초과해도 트랜잭션이 실패하지 않도록 방어적으로 자른다. (stories 컬럼 정의와 일치)
        const val STORY_TITLE_MAX_LENGTH = 100
        const val STORY_ONE_LINE_INTRO_MAX_LENGTH = 255

        // 주요 사건·엔딩 이름 컬럼(VARCHAR(100)) 초과를 방어적으로 자른다.
        const val STORY_MAIN_EVENT_NAME_MAX_LENGTH = 100
        const val STORY_ENDING_NAME_MAX_LENGTH = 100

        // 인물 이름 컬럼(VARCHAR(100)) 초과 방어. 매칭 키라 절단 후 이름으로 이미지를 붙인다.
        const val STORY_CHARACTER_NAME_MAX_LENGTH = 100

        // 인물 이미지 객체 키 접두사·확장자(스펙 §4-4). AI는 WebP만 보낸다.
        const val CHARACTER_IMAGE_KEY_PREFIX = "characters/generated"
        const val CHARACTER_IMAGE_EXTENSION = "webp"
        const val CHARACTER_IMAGE_DEFAULT_CONTENT_TYPE = "image/webp"

        // 크레딧 원장 소모·환불 행의 ref_type(연관 리소스 종류). 소모는 STORY 리소스를 가리킨다(스펙 §4-3-7).
        const val STORY_CREDIT_REF_TYPE = "STORY"

        // 완성 타이머(manyak.story.creation.duration)와 스토리라인 카운터(manyak.storyline.creation.result)가
        // 공유하는 outcome 태그 값(유한 enum 3종 — 의미는 recordCreationDuration·recordStorylineResult KDoc).
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val OUTCOME_REJECTED = "rejected"
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
        // 부모 링크 검증은 요청 행 삽입(recordOrRun 안의 별도 트랜잭션)보다 먼저 끝나야 한다 — 결과 3값이 그 삽입에 실린다.
        val parentLink = resolveParentLink(request, userId, deviceIdHashOrNull(deviceId))
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
        return recordOrRun(
            request.requestId,
            StoryCreationStage.STORYLINE_GENERATION,
            userId,
            deviceId,
            GenerateSimpleStorylinesResponse::class.java,
            parentLink,
            block = generate,
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
                parent == null -> ParentLinkError.NOT_FOUND
                !parent.hasSameChainOwnerAs(userId, deviceIdHash) -> ParentLinkError.OWNER_MISMATCH
                else -> null
            }
        }
        return ParentCreationLink(attempted, error)
    }

    /**
     * 소유자를 특정할 수 있으면 [storyCreationRequestRecorder]로 감싸 백그라운드 복구·멱등(스펙 §4-3-8)을 적용한다.
     * 단 소유자를 특정할 수 없는 게스트(디바이스 헤더 없음)는 요청 행을 기록하지 않고 그대로 실행해 문서화된 400을 낸다 —
     * 소유자 없는 요청 행을 남기면 아무도 소유할 수 없어, 올바른 디바이스로 재시도해도 409로 영구히 막힌다(Codex P2).
     */
    private fun <T : Any> recordOrRun(
        requestId: UUID,
        stage: StoryCreationStage,
        ownerUserId: Long?,
        deviceId: String?,
        responseType: Class<T>,
        // 요청 행에 함께 기록할 재생성 체인 부모 링크(KNK-755). 체인이 없는 경로(스토리 완성)는 null이다.
        parentLink: ParentCreationLink? = null,
        // 콜백 인자는 (이 실행이 회수(reclaim)인지 — 완성 경로의 reconcile 게이트, Codex P1), 호환되지 않는 replay의
        // fallback인지(중복 소모 방지), 요청 행에 실제로 기록된 체인(KNK-755)이다.
        block: (
            isReclaim: Boolean,
            isIncompatibleReplayFallback: Boolean,
            recordedParentLink: ParentCreationLink?,
        ) -> T,
    ): T {
        // 요청에 있는 식별자를 둘 다 저장한다(회원이어도 디바이스 해시를 버리지 않음) — 인증 상태가 바뀌어도 어느 한쪽으로 소유가 매칭되게(Codex P2).
        val ownerDeviceIdHash = deviceIdHashOrNull(deviceId)
        if (ownerUserId == null && ownerDeviceIdHash == null) {
            // 소유자를 특정할 수 없는 요청(회원도 아니고 디바이스 헤더도 없음)은 기록하지 않고 실행한다(소유자 없는 행 방지). 회수 아님.
            return block(false, false, parentLink)
        }
        return storyCreationRequestRecorder.execute(
            requestId,
            stage,
            ownerUserId,
            ownerDeviceIdHash,
            responseType,
            parentLink,
            block,
        )
    }

    /**
     * 스토리 완성 요청의 멱등·복구 소유자를 정한다(Codex P2). 회원이 소유한 세션은 그 회원만 완료할 수 있으므로,
     * 요청의 (만료·갱신으로 흔들리는) 임시 인증 신원 대신 세션 소유자를 소유자로 잡는다. 그래야 만료 토큰으로 게스트처럼
     * 보인 첫 시도가 남긴 행이 갱신 토큰 재시도를 소유 불일치 409로 영구히 막지 않는다. 익명 세션·미존재는 요청 신원을 쓴다.
     */
    private fun resolveCompletionOwnerUserId(simpleCreationId: Long, requestUserId: Long?): Long? =
        storyCreationSessionRepository.findById(simpleCreationId).orElse(null)?.userId ?: requestUserId

    /** 디바이스 헤더를 소유 판정용 해시로 변환한다(원문 저장·비교 금지 — DeviceIdHasher). 공백·null은 미소유(null). */
    private fun deviceIdHashOrNull(deviceId: String?): String? =
        deviceId?.takeIf { it.isNotBlank() }?.let(deviceIdHasher::hash)

    /**
     * 백그라운드 생성 복구 조회(스펙 §4-3-8). 소유 주체(회원 [userId] 또는 게스트 [deviceId])만 조회할 수 있고,
     * 미존재·타인 요청은 404다. [StoryCreationRequestStatusResponse.result]는 COMPLETED일 때 원 POST 응답 본문과 동일하다.
     */
    @Transactional(readOnly = true)
    fun getCreationRequest(
        requestId: UUID,
        userId: Long? = null,
        deviceId: String? = null,
    ): StoryCreationRequestStatusResponse {
        val deviceIdHash = deviceIdHashOrNull(deviceId)
        val row = storyCreationRequestRepository.findByRequestId(requestId)
            ?.takeIf { it.isOwnedBy(userId, deviceIdHash) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "생성 요청을 찾을 수 없습니다.")
        return StoryCreationRequestStatusResponse(
            stage = row.stage,
            status = row.status,
            result = row.resultJson
                ?.takeIf { row.status == StoryCreationRequestStatus.COMPLETED }
                ?.let { objectMapper.readTree(it) },
        )
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

        // AI 진입 신호. 이 지점을 지난 뒤의 실패는 상태 코드와 무관하게 실제 생성 실패다([storylineOutcomeOf]).
        onAiCallStarted()
        val aiResponse = try {
            aiCallRecorder.record(
                AiCallContext(feature = AiCallFeature.STORYLINE_GENERATION),
                meta = { it.meta?.toAiCallMeta() },
            ) {
                storyAiClient.createStorylines(aiRequest, storylineTraceLink(request, parentLink))
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
            val customTagsByKey = resolveCustomTags(customTagDrafts).associateBy { it.category to it.normalizedName }
            val creationSession = storyCreationSessionRepository.save(
                StoryCreationSession(
                    userId = userId,
                    status = StoryCreationSessionStatus.STORYLINES_GENERATED,
                    // 이 세션을 만든 스토리라인 요청의 request_id(KNK-751). 이후 컴파일·채팅이 같은 creation_id를 이어 쓴다.
                    storylineRequestId = request.requestId,
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
     * 응답을 재구성한다(스토리 완성 경로의 [reconcileCreatedSession]에 대응). 소유는 [StoryCreationRequestRecorder]가 isReclaim
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

    fun createSimpleStory(
        request: CreateSimpleStoryRequest,
        userId: Long? = null,
        deviceId: String? = null,
    ): SimpleStoryCreateResponse {
        suspensionGuard.requireActive(userId) // 정지 계정 소모·쓰기 차단(스펙 §4-5 B20, KNK-499). 요청 기록 전에 거부한다.
        // 완성 경로는 재생성 체인을 쓰지 않는다(체인은 스토리라인 단계의 개념).
        val create: (Boolean, Boolean, ParentCreationLink?) -> SimpleStoryCreateResponse = { isReclaim, _, _ ->
            val startNanos = System.nanoTime()
            // AI compile에 진입했는지. 실패 outcome을 rejected(밀리초 거부)와 failure(실제 생성 실패)로 가르는 기준이다
            // — HTTP 상태만으로는 compile을 마친 뒤 나는 4xx(세션 경합 409 등)를 구분할 수 없다([creationOutcomeOf]).
            var compileStarted = false
            structuredLogger.event("story_create_requested", "creation_id" to request.simpleCreationId)
            try {
                val outcome = doCreateSimpleStory(request, userId, deviceId, isReclaim) { compileStarted = true }
                // 시계는 한 번만 읽어 구조화 로그(duration_ms)와 메트릭이 같은 구간을 가리키게 한다.
                val durationNanos = System.nanoTime() - startNanos
                // aiCallLogId가 null이면 회수 재실행 재구성(reconcile) — AI·저장을 타지 않은 조회 경로라 측정에서 뺀다.
                if (outcome.aiCallLogId != null) {
                    recordCreationDuration(OUTCOME_SUCCESS, durationNanos)
                }
                structuredLogger.event(
                    "story_created",
                    "story_id" to outcome.response.id,
                    "ai_call_log_id" to outcome.aiCallLogId,
                    "duration_ms" to durationNanos / 1_000_000,
                )
                outcome.response
            } catch (exception: Exception) {
                val durationNanos = System.nanoTime() - startNanos
                recordCreationDuration(creationOutcomeOf(exception, compileStarted), durationNanos)
                structuredLogger.event(
                    "story_create_failed",
                    "error_code" to storyErrorCode(exception),
                    "duration_ms" to durationNanos / 1_000_000,
                )
                throw exception
            }
        }
        // 백그라운드 복구·멱등(스펙 §4-3-8): requestId로 요청을 추적하고, 재요청은 COMPLETED replay·PENDING 409·FAILED 재실행한다.
        // 소유자는 세션 소유권으로 정한다(요청 인증 신원이 만료·갱신으로 흔들려도 회원 소유 세션의 재시도가 막히지 않도록 — Codex P2).
        return recordOrRun(
            request.requestId,
            StoryCreationStage.STORY_COMPLETION,
            resolveCompletionOwnerUserId(request.simpleCreationId, userId),
            deviceId,
            SimpleStoryCreateResponse::class.java,
            block = create,
        )
    }

    /**
     * 간편 스토리 완성 처리시간을 `manyak.story.creation.duration`으로 집계한다(스펙 §4-7).
     *
     * outcome 태그는 3값이다(KNK-784):
     *   - `success`  : 실제로 생성·저장까지 끝난 호출
     *   - `failure`  : 생성을 시도하다 깨진 호출 — AI compile 실패(502·타임아웃)·응답 검증 실패·저장 경합. 수 초~180초.
     *   - `rejected` : 생성 시도 **이전에** 거부된 4xx — 세션 없음(404)·소유권(403)·이미 생성됨(409)·태그 오류(400)·
     *                  크레딧 부족과 게스트 한도(402). DB 조회 몇 번으로 끝나는 밀리초 경로다.
     *
     * 4xx를 failure에서 떼는 이유: 두 갈래를 한 히스토그램에 섞으면 거부 비중에 따라 실패 p95가 요동치고,
     * 거부가 늘수록 p95가 **낮아져** AI가 실제로 느려지는데 지표는 개선된 것처럼 보이는 역전이 생긴다.
     * 실패 건수 알림도 404 급증만으로 오발화한다. 태그 값이 2에서 3으로 늘지만 여전히 유한 enum이라 카디널리티는 안전하다.
     *
     * 실제 생성 콜백(create) 안에서만 부르고, **AI 호출 없이 저장된 결과를 돌려주는 조회 경로 두 가지는 의도적으로 제외한다**
     * — 포함하면 아주 짧은 시간이 섞여 p95가 실제 생성 비용보다 낙관적으로 왜곡된다.
     *   1. 멱등 재요청: [recordOrRun]의 COMPLETED replay (콜백 자체가 실행되지 않아 자연히 빠진다)
     *   2. 회수 재실행 재구성: [reconcileCreatedSession] (콜백은 타지만 AI·저장이 없다 — [StoryCreationOutcome.aiCallLogId]가
     *      null인 것으로 판별한다. 실제 생성 경로는 RecordedAiCall.aiCallLogId가 non-null이다)
     * 태그는 outcome 하나뿐이다(위 3값 — 고유값을 넣지 않는다는 카디널리티 규칙).
     */
    private fun recordCreationDuration(outcome: String, durationNanos: Long) {
        // 메트릭 기록 실패가 성공한 스토리 생성을 500으로 만들거나 실패 경로에서 원래 예외를 가리지 않도록 격리한다.
        runCatching {
            Timer.builder("manyak.story.creation.duration")
                .description("간편 스토리 완성 처리시간")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * 완성 실패 예외를 outcome 태그로 가른다([recordCreationDuration]의 rejected/failure 정의).
     *
     * **HTTP 상태만으로는 가를 수 없다.** compile을 마친 뒤에도 4xx가 날 수 있기 때문이다: 같은 세션에 requestId가
     * 다른 완성 요청 둘이 겹치면 둘 다 잠금 없는 초기 상태 검사를 통과해 compile을 호출하고, 진 쪽은
     * [compileAndPersist]가 `findByIdForUpdate`로 잠금을 잡은 뒤 STORY_CREATED를 보고 409(또는 세션 소실 시 404)를 던진다.
     * 이건 AI 호출을 이미 마친 수 초~180초짜리 **실제 생성 실패**라, 밀리초 거부와 같은 시계열에 넣으면 안 된다.
     *
     * 그래서 compile 진입 여부([compileStarted])를 먼저 본다.
     * - compile이 시작됐으면 상태 코드와 무관하게 failure다.
     * - 시작 전이면 4xx `ResponseStatusException`이 rejected다. 402 크레딧·게스트 한도를 던지는
     *   `CodedResponseStatusException`도 `ResponseStatusException` 하위라 함께 걸린다(별도 분기 불필요).
     * - 그 외(5xx·비 HTTP 예외)는 failure다.
     */
    private fun creationOutcomeOf(exception: Exception, compileStarted: Boolean): String = when {
        compileStarted -> OUTCOME_FAILURE
        exception is ResponseStatusException && exception.statusCode.is4xxClientError -> OUTCOME_REJECTED
        else -> OUTCOME_FAILURE
    }

    /**
     * 스토리라인 생성 결과 분포를 `manyak.storyline.creation.result`로 집계한다(KNK-801).
     *
     * outcome 태그 값은 완성 타이머([recordCreationDuration])와 같은 3값이다:
     *   - `success`  : 스토리라인 생성·저장까지 끝난 호출
     *   - `failure`  : AI 호출에 진입한 뒤 깨진 호출 — AI 실패(502)·타임아웃·저장 실패
     *   - `rejected` : AI 호출 **이전에** 거부된 4xx — 게스트 한도 소진(402)·사용할 수 없는 태그 ID(400) 등
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
     * 그래서 AI 진입 여부([aiCallStarted])를 먼저 본다.
     *
     * 여기서 `Throwable`을 받는 것은 호출부가 `Throwable`을 잡기 때문이다(게스트 한도 복원이 Error에도 돌아야 한다).
     */
    private fun storylineOutcomeOf(throwable: Throwable, aiCallStarted: Boolean): String = when {
        aiCallStarted -> OUTCOME_FAILURE
        throwable is ResponseStatusException && throwable.statusCode.is4xxClientError -> OUTCOME_REJECTED
        else -> OUTCOME_FAILURE
    }

    private fun storyErrorCode(exception: Exception): String = when (exception) {
        is ResponseStatusException ->
            HttpStatus.resolve(exception.statusCode.value())?.name ?: exception.statusCode.toString()
        else -> exception::class.simpleName ?: "UNKNOWN"
    }

    private fun doCreateSimpleStory(
        request: CreateSimpleStoryRequest,
        userId: Long?,
        deviceId: String?,
        // 소유 검증된 회수(reclaim)인지. 완성된 세션의 스토리 재구성은 회수일 때만 허용한다(Codex P1 — 신규 requestId로 남의 스토리 열람 차단).
        isReclaim: Boolean,
        // AI compile 진입 직전에 호출한다. 호출부가 실패 outcome을 rejected/failure로 가르는 데 쓴다([creationOutcomeOf]).
        onCompileStarted: () -> Unit,
    ): StoryCreationOutcome {
        val session = storyCreationSessionRepository.findById(request.simpleCreationId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "간편 제작 진행 정보를 찾을 수 없습니다.")
            }

        // 다단계 간편 제작 소유권 강제(Codex PR #76 P2): AI 호출(비용) 전에 검사·거부한다.
        // - 익명 세션(소유자 없음): 이 finalize 요청의 인증 사용자(또는 익명)에 귀속.
        // - 소유 세션(소유자 있음): 같은 사용자만 완료 가능. 다른 사용자/익명(만료·무효 토큰 포함)이 simpleCreationId로
        //   남의 진행을 가로채 자기 user_id로 기록하거나, 소유 세션을 익명으로 떨어뜨리는 것을 막는다.
        // 재구성(아래 STORY_CREATED 경로)보다 먼저 검사해 완료된 스토리가 비소유자에게 새지 않게 한다.
        val attributedUserId = when {
            session.userId == null -> userId
            session.userId == userId -> userId
            else -> throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "본인이 시작한 간편 제작만 완료할 수 있습니다.",
            )
        }

        // 이미 완료된 세션을 만나면(P2-10, KNK-635/644): 이 세션을 만든 바로 그 요청의 회수(reclaim)일 때만 409 대신 저장된 스토리로
        // 응답을 재구성해 돌려준다. 크래시로 요청 행의 COMPLETED 마킹을 잃어(별도 트랜잭션) PENDING으로 남은 뒤 회수될 때, 잃은 story id를 되찾는 경로.
        // 회수 여부(isReclaim)에 더해 session.creationRequestId == 요청 request_id를 검증한다: 소유자 없는 게스트 세션도 이 바인딩으로
        // 정당한 요청자에게 묶여, 순차 simpleCreationId를 찍거나 비소유 aged PENDING을 스왑해 남의 스토리를 열람하는 것을 막는다(Codex P1 x3).
        // AI·저장을 다시 타지 않아 중복 생성·중복 과금이 없다.
        if (session.status == StoryCreationSessionStatus.STORY_CREATED) {
            if (isReclaim && session.creationRequestId == request.requestId) {
                return reconcileCreatedSession(session)
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
        }

        val selectedStoryline = storyCreationStorylineRepository
            .findByIdAndCreationSessionId(request.storylineId, request.simpleCreationId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "선택한 스토리라인을 찾을 수 없습니다.")

        val sessionTagRows = storyCreationSessionTagRepository
            .findAllWithTagByCreationSessionId(request.simpleCreationId)
        val genreTags = selectGenreTags(sessionTagRows)
        val characters = storyCreationCharacterRepository
            .findAllByCreationSessionIdOrderByRoleAscSortOrderAscIdAsc(request.simpleCreationId)
        // 세션 스코프(character_id NULL) 태그를 카테고리로 복원하는 폴백. 이름·성별은 저장돼 있지 않아 null이고 AI가 생성한다.
        // 주인공 인물 행이 없는 세션(구 계약 세션, 또는 스키마상 가능한 주변 인물만 있는 세션)의 주인공 특징·이동 태그 유실을 막는다.
        val featureNamesOf = { category: SimpleStoryTagCategory ->
            sessionTagRows.map { it.tag }.filter { it.category == category }.distinctBy { it.normalizedName }.map { it.name }
        }
        // 인물 행이 있으면 저장 인물(character_id)별로, 없으면(KNK-845 배포 전 생성된 구 세션) 세션 태그를 카테고리로 복원한다.
        val (aiProtagonist, aiSupportingCharacters) = if (characters.isNotEmpty()) {
            // 특징은 저장 인물별로 되싣는다. character는 LAZY지만 FK id는 초기화 없이 읽힌다(그룹핑 키로만 사용).
            val featureNamesByCharacterId = sessionTagRows
                .filter { it.character != null }
                .groupBy { it.character!!.id }
                .mapValues { (_, rows) -> rows.map { it.tag }.distinctBy { it.normalizedName }.map { it.name } }
            val protagonist = characters.firstOrNull { it.role == StoryCreationCharacterRole.PROTAGONIST }
            val supporting = characters.filter { it.role == StoryCreationCharacterRole.SUPPORTING_CHARACTER }
            // 주인공 행이 있으면 인물 단위로, 없으면(주변 인물만 존재하는 세션) category 폴백으로 복원한다 —
            // character_id NULL인 주인공 특징(이동 태그 포함)이 유실되지 않게 한다(주변 인물 조립은 그대로).
            (protagonist?.toAiCharacter(featureNamesByCharacterId)
                ?: AiCharacter(features = featureNamesOf(SimpleStoryTagCategory.PROTAGONIST))) to
                supporting.map { it.toAiCharacter(featureNamesByCharacterId) }
        } else {
            // 인물 행이 전혀 없는 구 세션: 주인공·주변 인물 모두 category 폴백.
            val supportingFeatures = featureNamesOf(SimpleStoryTagCategory.SUPPORTING_CHARACTER)
            AiCharacter(features = featureNamesOf(SimpleStoryTagCategory.PROTAGONIST)) to
                if (supportingFeatures.isEmpty()) emptyList() else listOf(AiCharacter(features = supportingFeatures))
        }
        // 스토리 장르로 로어북을 선별해 compile 요청에 세계관·용어 확장 재료로 싣는다(스펙 §4-3-6·§5-3-3).
        // 전달분은 저장 성공 시 story_lorebooks에 연결한다(compileAndPersist).
        val selectedLorebooks = selectLorebooksForGenres(genreTags)
        val aiRequest = AiStoryCompileRequest(
            genreTags = genreTags.map { it.name },
            protagonist = aiProtagonist,
            supportingCharacters = aiSupportingCharacters,
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
            compileAndPersist(
                session,
                attributedUserId,
                selectedStoryline,
                genreTags,
                selectedLorebooks,
                aiRequest,
                request.requestId,
                // AI trace 여정(KNK-751): creation_id는 이 세션의 **스토리라인 단계** request_id다(완성 단계 requestId가 아니다).
                // 이 컬럼 도입 전 세션은 null이라 헤더가 생략된다. 재생성 여부는 프론트가 준 값만 전달한다.
                AiTraceLink(
                    creationId = session.storylineRequestId,
                    // 스토리라인 id는 Long 그대로다(스펙 §4-4: 생성 퍼널의 임시 리소스라 소유 개념이 없어 Long 노출 확정).
                    // 같은 값이 compile 요청 본문에도 실려 있어 헤더에 넣는 것이 새 노출은 아니다.
                    storylineId = selectedStoryline.id,
                    storylineOrder = selectedStoryline.storylineOrder,
                    isRegenerated = request.isRegenerated,
                ),
                onCompileStarted,
            )
        }
    }

    /**
     * 회수 재실행(P2-10, KNK-635/644)이 이미 STORY_CREATED인 세션을 만나면, [StoryCreationSession.storyId]가 가리키는
     * 저장된 스토리로 원 POST 응답([SimpleStoryCreateResponse])을 재구성한다. [compileAndPersist]가 만든 응답과 같은 모양이며,
     * AI·저장을 다시 타지 않는다. 검증(회수 여부 + `creationRequestId == request_id` 바인딩)은 호출부([doCreateSimpleStory])가
     * 선행하므로, 소유자 없는 게스트 세션도 이 세션을 만든 요청에만 안전하게 열린다(Codex P1 바인딩).
     *
     * storyId·자식 행이 없으면(비정상 상태) 원래의 409로 폴백한다.
     */
    private fun reconcileCreatedSession(session: StoryCreationSession): StoryCreationOutcome {
        val alreadyCreated = ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
        val storyId = session.storyId ?: throw alreadyCreated
        val story = storyRepository.findById(storyId).orElseThrow { alreadyCreated }
        val startSetting = storyStartSettingRepository.findFirstByStoryIdOrderByIdAsc(storyId) ?: throw alreadyCreated

        val suggestedInputs = storySuggestedInputRepository
            .findByStartSettingIdOrderByInputOrderAsc(startSetting.id)
            .map { it.inputText }
        val endings = storyEndingRepository
            .findByStartSettingIdAndEnabledTrueOrderBySortOrderAsc(startSetting.id)
            .map { it.toEndingResponse() }
        // 응답의 genres는 세션 장르 태그로 재구성한다 — compile 경로와 같은 selectGenreTags를 써 순서를 일치시킨다(KNK-861).
        val genres = selectGenreTags(storyCreationSessionTagRepository.findAllWithTagByCreationSessionId(session.id))
            .map { it.name }

        return StoryCreationOutcome(
            response = SimpleStoryCreateResponse(
                id = story.publicId.toString(),
                title = story.title,
                oneLineIntro = story.oneLineIntro,
                description = story.description,
                genres = genres,
                startSettings = listOf(
                    StoryStartSettingResponse(
                        id = startSetting.publicId.toString(),
                        name = startSetting.name,
                        prologue = startSetting.prologue,
                        startSituation = startSetting.startSituation,
                        suggestedInputs = suggestedInputs,
                        endings = endings,
                    ),
                ),
            ),
            aiCallLogId = null,
        )
    }

    /**
     * 세션 태그 행에서 장르 태그를 선별한다(카테고리 필터 + 정규화 키 중복 제거 + 정렬).
     * compile 경로와 완성 회수 경로가 공유한다 — 규칙이 복붙돼 한쪽만 고쳐지면 회수 응답 순서가 최초 응답과 어긋난다(KNK-861).
     *
     * 장르는 세션 스코프(character 없음). 스토리라인 경로와 같은 정규화 키 기준으로 중복 제거한다.
     * 정렬 1순위가 tagSource인 이유(KNK-859): CUSTOM은 sortOrder 기본값이 0이라 이 기준이 없으면 직접 입력 장르가
     * 시드 sortOrder를 가진 제공 장르를 앞지른다. 그러면 스토리라인 응답의 '사전 정의 → 직접 입력' 순서와 어긋나고,
     * storyThumbnailLinker가 직접 입력 장르를 첫 장르로 보게 돼 제공 장르에 맞는 썸네일이 있어도 폴백으로 떨어진다.
     */
    private fun selectGenreTags(sessionTagRows: List<StoryCreationSessionTag>): List<StoryCreationTag> = sessionTagRows
        .map { it.tag }
        .filter { it.category == SimpleStoryTagCategory.GENRE }
        .distinctBy { it.normalizedName }
        .sortedWith(compareBy({ it.tagSource == StoryCreationTagSource.CUSTOM }, { it.sortOrder }, { it.id }))

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
        // 이 완성을 수행하는 생성 요청의 request_id. STORY_CREATED와 함께 세션에 박아, 회수 재실행이 이 요청인지 검증한다(KNK-644).
        requestId: UUID,
        // Langfuse trace 연결 식별자(KNK-751). 위 requestId(완성 단계)와 달리 creation_id는 스토리라인 단계 값이다.
        traceLink: AiTraceLink,
        // compile 진입 신호. 이 지점을 지난 뒤의 실패는 상태 코드와 무관하게 실제 생성 실패다([creationOutcomeOf]).
        onCompileStarted: () -> Unit,
    ): StoryCreationOutcome {
        onCompileStarted()
        val recorded = try {
            aiCallRecorder.record(
                AiCallContext(feature = AiCallFeature.STORY_COMPLETION),
                meta = { it.meta?.toAiCallMeta() },
            ) {
                storyAiClient.compileStory(aiRequest, traceLink)
            }
        } catch (exception: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리 생성 요청에 실패했습니다.", exception)
        }
        val aiResponse = recorded.result

        val genre = genreTags.joinToString(separator = ", ") { it.name }.ifEmpty { null }

        // 스토리 public_id를 트랜잭션 밖에서 미리 확정한다 — 인물 이미지 객체 키가 이 값을 쓰는데,
        // 업로드(네트워크 I/O)를 DB 트랜잭션 안에서 하면 커넥션을 수 초간 잡아 둔다.
        val storyPublicId = UUID.randomUUID()
        // 이름 정규화를 업로드보다 먼저 한 곳에서 끝낸다 — 저장 단계에서 걸러질 인물을 업로드하면 고아 객체가 된다.
        val appearancesByName = normalizeByName(aiResponse.characterAppearances) { it.name }
        val imagesByName = normalizeByName(aiResponse.characterImages) { it.name }
        // 이미지 업로드는 트랜잭션 밖에서 끝내고, 성공한 URL만 트랜잭션 안에서 story_characters에 저장한다.
        val uploadedImages = uploadCharacterImages(storyPublicId, imagesByName)

        return runWithCharacterImageCleanup(storyPublicId, uploadedImages) {
            transactionTemplate.execute {
                val lockedSession = storyCreationSessionRepository.findByIdForUpdate(session.id)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "간편 제작 진행 정보를 찾을 수 없습니다.")
                if (lockedSession.status == StoryCreationSessionStatus.STORY_CREATED) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "이미 스토리가 생성된 간편 제작 진행입니다.")
                }

                selectedStoryline.isSelected = true
                storyCreationStorylineRepository.save(selectedStoryline)

                val story = storyRepository.save(
                    Story(
                        publicId = storyPublicId,
                        userId = attributedUserId,
                        title = aiResponse.stories.title.take(STORY_TITLE_MAX_LENGTH),
                        oneLineIntro = aiResponse.stories.oneLineIntro.take(STORY_ONE_LINE_INTRO_MAX_LENGTH),
                        description = aiResponse.stories.description,
                        genre = genre,
                        // 표지는 등록 시 1회 확정한다(§4-3-9). 후보가 없으면 null이고 프론트엔드가 placeholder를 그린다.
                        thumbnailImageKey = storyThumbnailLinker.linkFor(genreTags.map { it.name }),
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

                persistStoryCharacters(story, appearancesByName, imagesByName.keys, uploadedImages)

                // 익명 세션을 로그인 사용자가 완료(claim)하면 세션 소유자도 그 사용자로 박는다 — 안 그러면 그 스토리의
                // 스토리라인 평가 소유권 검사(session.userId 기반)가 세션을 익명으로 보아 아무나 평가/취소할 수 있다(Codex PR #76 P2).
                lockedSession.userId = attributedUserId
                lockedSession.status = StoryCreationSessionStatus.STORY_CREATED
                lockedSession.storyId = story.id
                // 회수 재실행 검증용 바인딩(KNK-644): 이 세션을 만든 요청의 request_id를 STORY_CREATED와 원자적으로 커밋한다.
                lockedSession.creationRequestId = requestId
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
    }

    /** 업로드한 객체 키와 그 서빙 URL. 저장 실패 시 보상 삭제에 객체 키가 필요해 URL만 들고 다니지 않는다. */
    private data class UploadedCharacterImage(val objectKey: String, val url: String)

    /**
     * 인물 이름을 정규화해 `이름 → 항목` 맵으로 만든다(스펙 §5-3-3의 name 매칭 키).
     *
     * trim → 컬럼 길이(100자) 절단 → 빈 이름 제외 → 같은 이름은 **첫 항목만** 남긴다. 절단으로 앞 100자가
     * 같아진 서로 다른 이름도 여기서 하나로 합쳐진다. 이 정규화를 업로드보다 먼저 한 곳에서 끝내야
     * 저장되지 않을 인물의 이미지를 S3에 올려 고아로 남기는 일이 없다.
     */
    private fun <T> normalizeByName(items: List<T>, nameOf: (T) -> String): Map<String, T> {
        val normalized = LinkedHashMap<String, T>()
        items.forEach { item ->
            val name = nameOf(item).trim().take(STORY_CHARACTER_NAME_MAX_LENGTH)
            if (name.isNotEmpty()) {
                normalized.putIfAbsent(name, item)
            }
        }
        return normalized
    }

    /**
     * 컴파일이 생성한 인물 이미지를 S3에 올리고 `인물 이름 → 업로드 결과` 맵을 만든다(스펙 §4-4, KNK-966).
     *
     * **DB 트랜잭션 밖에서** 호출한다(네트워크 I/O가 커넥션을 붙잡지 않게). 디코딩·업로드 실패는 인물 단위로 흡수해
     * 그 인물만 이미지 없이 저장되게 하고, 스토리 생성은 계속한다(graceful — 엔딩 빈 배열과 같은 원칙).
     * [AiCharacterImage.error]가 있는 항목은 base64가 함께 와도 실패로 보고 건너뛴다(에러 코드가 우선).
     */
    private fun uploadCharacterImages(
        storyPublicId: UUID,
        imagesByName: Map<String, AiCharacterImage>,
    ): Map<String, UploadedCharacterImage> = imagesByName.mapNotNull { (name, image) ->
        if (image.error != null) {
            return@mapNotNull null
        }
        val base64 = image.imageBase64?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 디코딩은 업로드 밖에서 먼저 끝낸다 — 깨진 base64는 올라간 객체가 없으니 아래 보상 삭제도 필요 없다.
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (exception: Exception) {
            logCharacterImageEvent("character_image_upload_failed", storyPublicId, exception)
            return@mapNotNull null
        }
        val objectKey = "$CHARACTER_IMAGE_KEY_PREFIX/$storyPublicId/${UUID.randomUUID()}.$CHARACTER_IMAGE_EXTENSION"
        val url = try {
            characterImageStorage.upload(
                objectKey,
                bytes,
                image.contentType?.takeIf { it.isNotBlank() } ?: CHARACTER_IMAGE_DEFAULT_CONTENT_TYPE,
            )
        } catch (exception: Exception) {
            // 이미지 한 장 때문에 스토리 생성이 실패해서는 안 된다. 사유만 남기고 이미지 없이 진행한다.
            logCharacterImageEvent("character_image_upload_failed", storyPublicId, exception)
            // 예외가 났어도 저장소는 객체를 받았을 수 있다(응답만 유실된 경우). 이 키는 반환값에 실리지 않아
            // 트랜잭션 보상 삭제가 닿지 못하므로 여기서 지운다. 실제로 안 올라갔으면 삭제는 무해한 no-op이다.
            deleteCharacterImageQuietly(storyPublicId, objectKey)
            null
        }
        url?.let { name to UploadedCharacterImage(objectKey, it) }
    }.toMap()

    /**
     * [block]이 예외로 끝나면 이미 올린 인물 이미지를 best-effort로 지우고 원래 예외를 다시 던진다.
     *
     * 업로드는 트랜잭션보다 먼저 끝나므로, 저장이 409·502·DB 오류로 깨지면 객체 키가 어디에도 기록되지 않은
     * 고아 객체가 남는다(키가 매번 UUID라 재시도로도 회수되지 않는다). 삭제 실패는 로그만 남긴다 —
     * 정리 실패가 원래 실패 원인을 가려서는 안 된다.
     */
    private fun <T> runWithCharacterImageCleanup(
        storyPublicId: UUID,
        uploadedImages: Map<String, UploadedCharacterImage>,
        block: () -> T,
    ): T {
        try {
            return block()
        } catch (throwable: Throwable) {
            if (uploadedImages.isNotEmpty() && isStoryAbsent(storyPublicId)) {
                uploadedImages.values.forEach { uploaded -> deleteCharacterImageQuietly(storyPublicId, uploaded.objectKey) }
            }
            throw throwable
        }
    }

    /**
     * 보상 삭제를 해도 되는지 판정한다 — 스토리 행이 **없을 때만** true다.
     *
     * 커밋이 서버에 반영됐는데 응답만 유실되면 예외가 나도 스토리와 `image_url`이 영구히 남는다. 그때 객체를 지우면
     * DB가 가리키는 이미지가 깨져 사용자에게 보인다. 그래서 삭제 전에 트랜잭션 밖에서(이 시점엔 실패한 트랜잭션이
     * 이미 끝나 있다) 스토리 존재를 확인하고, 남아 있으면 지우지 않는다.
     *
     * 확인 자체가 실패해도 지우지 않는다. **고아 객체(저장 비용)가 깨진 이미지(사용자 피해)보다 싸다**는 판단이다.
     */
    private fun isStoryAbsent(storyPublicId: UUID): Boolean = try {
        val exists = storyRepository.existsByPublicId(storyPublicId)
        if (exists) {
            structuredLogger.event(
                "character_image_cleanup_skipped",
                "story_public_id" to storyPublicId.toString(),
                "reason" to "story_exists",
            )
        }
        !exists
    } catch (exception: Exception) {
        structuredLogger.event(
            "character_image_cleanup_skipped",
            "story_public_id" to storyPublicId.toString(),
            "reason" to "lookup_failed",
            "error_type" to (exception::class.simpleName ?: "UNKNOWN"),
        )
        false
    }

    /** 고아가 될 객체를 지운다. 삭제 실패는 로그만 남긴다 — 정리 실패가 원래 실패 원인을 가려서는 안 된다. */
    private fun deleteCharacterImageQuietly(storyPublicId: UUID, objectKey: String) {
        try {
            characterImageStorage.delete(objectKey)
        } catch (exception: Exception) {
            logCharacterImageEvent("character_image_cleanup_failed", storyPublicId, exception)
        }
    }

    /** 인물 이미지 실패 관측용 구조화 로그. base64·URL은 싣지 않는다(개인정보·용량). */
    private fun logCharacterImageEvent(eventName: String, storyPublicId: UUID, exception: Exception) {
        structuredLogger.event(
            eventName,
            "story_public_id" to storyPublicId.toString(),
            "error_type" to (exception::class.simpleName ?: "UNKNOWN"),
        )
    }

    /**
     * 컴파일 산출물의 인물을 `story_characters`에 저장한다(스펙 §4-4 · §5-3-3).
     *
     * 이름은 [normalizeByName]이 이미 정규화·중복 제거했다. `character_appearances`는 인물 전원,
     * `character_images`는 외형이 있는 인물만이라 길이가 다를 수 있어 이름으로 매칭하며, 이미지만 있고 외형이
     * 없는 이름도(계약상 없어야 하지만) 행을 만들어 업로드한 이미지를 잃지 않는다.
     */
    private fun persistStoryCharacters(
        story: Story,
        appearancesByName: Map<String, AiCharacterAppearance>,
        imageNames: Set<String>,
        uploadedImages: Map<String, UploadedCharacterImage>,
    ) {
        val names = (appearancesByName.keys + imageNames).toList()
        if (names.isEmpty()) {
            return
        }
        storyCharacterRepository.saveAll(
            names.map { name ->
                val appearance = appearancesByName[name]
                StoryCharacter(
                    story = story,
                    name = name,
                    imageUrl = uploadedImages[name]?.url,
                    gender = appearance?.gender,
                    age = appearance?.age,
                    body = appearance?.body,
                    face = appearance?.face,
                    hair = appearance?.hair,
                    outfit = appearance?.outfit,
                    visualIdentity = appearance?.visualIdentity,
                )
            },
        )
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

    /** 컴파일 경로: 저장된 인물과 [featureNamesByCharacterId](character_id→특징명)로 인물 단위 AI 입력을 되싣는다. */
    private fun StoryCreationCharacter.toAiCharacter(featureNamesByCharacterId: Map<Long, List<String>>): AiCharacter =
        AiCharacter(
            name = name,
            gender = gender?.name,
            features = featureNamesByCharacterId[id].orEmpty(),
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

    private data class StoryCreationOutcome(
        val response: SimpleStoryCreateResponse,
        // 회수 재실행이 저장된 스토리를 재구성해 돌려주는 경로(P2-10)는 AI를 호출하지 않으므로 null이다.
        val aiCallLogId: Long?,
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
