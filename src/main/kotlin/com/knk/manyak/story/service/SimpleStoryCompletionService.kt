package com.knk.manyak.story.service

import com.knk.manyak.auth.security.SuspensionGuard
import com.knk.manyak.credit.InsufficientCreditException
import com.knk.manyak.credit.dto.CreditReason
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.error.ApiErrorCodes
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.aicall.AiCallContext
import com.knk.manyak.global.observability.aicall.AiCallFeature
import com.knk.manyak.global.observability.aicall.AiCallRecorder
import com.knk.manyak.image.service.ImageStageBudget
import com.knk.manyak.story.client.AiCharacter
import com.knk.manyak.story.client.AiLorebookItem
import com.knk.manyak.story.client.AiStoryCompileRequest
import com.knk.manyak.story.client.StoryAiClient
import com.knk.manyak.story.client.StoryContentRejectedException
import com.knk.manyak.story.dto.CreateSimpleStoryRequest
import com.knk.manyak.story.dto.ParentCreationLink
import com.knk.manyak.story.dto.SimpleStoryCreateResponse
import com.knk.manyak.story.dto.SimpleStoryTagCategory
import com.knk.manyak.story.dto.StoryCreationCharacterRole
import com.knk.manyak.story.dto.StoryCreationSessionStatus
import com.knk.manyak.story.dto.StoryCreationStage
import com.knk.manyak.story.dto.StoryVisibility
import com.knk.manyak.story.entity.Lorebook
import com.knk.manyak.story.entity.StoryCreationCharacter
import com.knk.manyak.story.entity.StoryCreationSession
import com.knk.manyak.story.entity.StoryCreationStoryline
import com.knk.manyak.story.entity.StoryCreationTag
import com.knk.manyak.story.event.StoryCompletedEvent
import com.knk.manyak.story.repository.LorebookRepository
import com.knk.manyak.story.repository.StoryCreationCharacterRepository
import com.knk.manyak.story.repository.StoryCreationSessionRepository
import com.knk.manyak.story.repository.StoryCreationSessionTagRepository
import com.knk.manyak.story.repository.StoryCreationStorylineRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 간편 제작 완성 단계(스펙 4-1 간편 제작 절). 선택한 후보로 완성 요청을 검사하고 과금한 뒤 컴파일 결과를
 * [GeneratedStoryImageUploader]와 [SimpleStoryCompletionWriter]에 넘기고, 실패하면 환불한다(KNK-2056에서 SimpleStoryCreationService를 나눔).
 * 보상 순서는 환불 래퍼 안에 업로드 보상 래퍼, 그 안에 저장이다. 요청 기록과 멱등은 [StoryCreationRequestRecorder]가 맡는다.
 */
@Service
class SimpleStoryCompletionService(
    private val storyCreationSessionRepository: StoryCreationSessionRepository,
    private val storyCreationCharacterRepository: StoryCreationCharacterRepository,
    private val storyCreationSessionTagRepository: StoryCreationSessionTagRepository,
    private val storyCreationStorylineRepository: StoryCreationStorylineRepository,
    private val lorebookRepository: LorebookRepository,
    private val generatedStoryImageUploader: GeneratedStoryImageUploader,
    private val simpleStoryCompletionWriter: SimpleStoryCompletionWriter,
    private val storyAiClient: StoryAiClient,
    private val artStyleCatalogService: ArtStyleCatalogService,
    private val structuredLogger: StructuredLogger,
    private val meterRegistry: MeterRegistry,
    private val aiCallRecorder: AiCallRecorder,
    private val creditWalletService: CreditWalletService,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val suspensionGuard: SuspensionGuard,
    private val storyCreationRequestRecorder: StoryCreationRequestRecorder,
    // 환불과 횟수 복원 결과 기록(KNK-2081). 되돌리기가 실패해도 원래 예외를 다시 던진다.
    private val refunds: StoryCreationRefunds,
    // 간편 제작 1회 소모 크레딧. 운영 중 조정 가능한 정책값이라 요청마다 해석한다(KNK-1056).
    private val creditPolicyService: CreditPolicyService,
    // 완성 푸시 발행(KNK-1115). 수신은 커밋 뒤(StoryCompletionPushListener)라 발송 실패가 생성을 되돌리지 않는다.
    private val eventPublisher: ApplicationEventPublisher,
) {
    /**
     * 스토리 완성 요청의 멱등·복구 소유자를 정한다(Codex P2). 회원이 소유한 세션은 그 회원만 완료할 수 있으므로,
     * 요청의 (만료·갱신으로 흔들리는) 임시 인증 신원 대신 세션 소유자를 소유자로 잡는다. 그래야 만료 토큰으로 게스트처럼
     * 보인 첫 시도가 남긴 행이 갱신 토큰 재시도를 소유 불일치 409로 영구히 막지 않는다. 익명 세션·미존재는 요청 신원을 쓴다.
     */
    private fun resolveCompletionOwnerUserId(simpleCreationId: Long, requestUserId: Long?): Long? =
        storyCreationSessionRepository.findById(simpleCreationId).orElse(null)?.userId ?: requestUserId

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
        val completionOwnerUserId = resolveCompletionOwnerUserId(request.simpleCreationId, userId)
        return storyCreationRequestRecorder.recordOrRun(
            request.requestId,
            StoryCreationStage.STORY_COMPLETION,
            completionOwnerUserId,
            deviceId,
            SimpleStoryCreateResponse::class.java,
            block = create,
            draftId = request.draftId.takeIf { userId != null && userId == completionOwnerUserId },
            // 완성 푸시(KNK-1115). 요청 행이 COMPLETED로 **커밋된 뒤** 제작자에게 알린다. 발행은 COMPLETED
            // 마킹과 같은 트랜잭션이라 마킹이 롤백되면 발송도 없고, 멱등 replay는 이 지점에 오지 않아
            // 재요청으로 중복 발송되지 않는다. 값은 저장된 result_json을 다시 읽지 않고 방금 만든 응답에서 꺼낸다.
            onCompleted = completionOwnerUserId?.let { ownerId ->
                { response: SimpleStoryCreateResponse ->
                    val event = StoryCompletedEvent(userId = ownerId, requestId = request.requestId, storyPublicId = response.id, title = response.title)
                    // StoryCreationRequestRecorder가 연 완료 기록 트랜잭션에서 구독자가 내역과 원격 아웃박스를 함께 쓴다.
                    eventPublisher.publishEvent(event)
                }
            },
        )
    }

    /**
     * 간편 스토리 완성 처리시간을 `manyak.story.creation.duration`으로 집계한다(스펙 §4-7).
     *
     * outcome 태그는 3값이다(KNK-784):
     *   - `success`  : 실제로 생성·저장까지 끝난 호출
     *   - `failure`  : 생성을 시도하다 깨진 호출 — AI compile 실패(502·타임아웃)·응답 검증 실패·저장 경합. 수 초~180초.
     *   - `rejected` : AI 내용 거절 또는 생성 시도 **이전에** 거부된 4xx — 세션 없음(404)·소유권(403)·이미 생성됨(409)·태그 오류(400)·
     *                  크레딧 부족과 게스트 한도(402). 내용 거절 이외에는 DB 조회 몇 번으로 끝난다.
     *
     * 4xx를 failure에서 떼는 이유: 두 갈래를 한 히스토그램에 섞으면 거부 비중에 따라 실패 p95가 요동치고,
     * 거부가 늘수록 p95가 **낮아져** AI가 실제로 느려지는데 지표는 개선된 것처럼 보이는 역전이 생긴다.
     * 실패 건수 알림도 404 급증만으로 오발화한다. 태그 값이 2에서 3으로 늘지만 여전히 유한 enum이라 카디널리티는 안전하다.
     *
     * 실제 생성 콜백(create) 안에서만 부르고, **AI 호출 없이 저장된 결과를 돌려주는 조회 경로 두 가지는 의도적으로 제외한다**
     * — 포함하면 아주 짧은 시간이 섞여 p95가 실제 생성 비용보다 낙관적으로 왜곡된다.
     *   1. 멱등 재요청: [recordOrRun]의 COMPLETED replay (콜백 자체가 실행되지 않아 자연히 빠진다)
     *   2. 회수 재실행 재구성: [SimpleStoryCompletionWriter.reconcileCreatedSession] (콜백은 타지만 AI·저장이 없다 — [StoryCreationOutcome.aiCallLogId]가
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
     * AI 내용 거절은 진입 뒤에도 rejected다. 그 외에는 compile 진입 여부([compileStarted])를 먼저 본다.
     * - AI 내용 거절을 제외하고 compile이 시작됐으면 상태 코드와 무관하게 failure다.
     * - 시작 전이면 4xx `ResponseStatusException`이 rejected다. 402 크레딧·게스트 한도를 던지는
     *   `CodedResponseStatusException`도 `ResponseStatusException` 하위라 함께 걸린다(별도 분기 불필요).
     * - 그 외(5xx·비 HTTP 예외)는 failure다.
     */
    private fun creationOutcomeOf(exception: Exception, compileStarted: Boolean): String = when {
        exception is CodedResponseStatusException && exception.errorCode == ApiErrorCodes.CONTENT_REJECTED -> OUTCOME_REJECTED
        compileStarted -> OUTCOME_FAILURE
        exception is ResponseStatusException && exception.statusCode.is4xxClientError -> OUTCOME_REJECTED
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
                return simpleStoryCompletionWriter.reconcileCreatedSession(session)
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
        val artStyle = artStyleCatalogService.resolve(request.artStyle)
        val aiRequest = AiStoryCompileRequest(
            artStyle = artStyle,
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
        // 소모 정책값은 이 요청 안에서 한 번만 읽어 차감·환불에 같은 값을 쓴다(KNK-1056). 차감과 환불 사이에
        // 정책이 바뀌면 금액이 어긋나 사용자가 손해를 보거나 이득을 본다(환불은 원장 행이 아니라 이 값을 쓴다).
        val storyCreationCost = creditPolicyService.amountOf(CreditPolicyKey.STORY_CREATION_COST)
        val guestDeviceId = guestTrialLimitService.reserveForGuestOrNull(
            attributedUserId,
            deviceId,
            GuestTrialLimitService.Counter.STORY_CREATION,
        )
        // 회원 소모 2단(스펙 §4-3-7 B13): 계정 귀속 체험 잔여가 있으면 먼저 무료로 소진하고, 없으면 크레딧을 선차감한다.
        val memberTrialCovered = attributedUserId != null &&
            guestTrialLimitService.reserveMember(attributedUserId, GuestTrialLimitService.Counter.STORY_CREATION)
        if (attributedUserId != null && !memberTrialCovered) {
            chargeStoryCreation(attributedUserId, refId = session.id, amount = storyCreationCost)
        }

        return runWithRefundOnFailure(
            userId = attributedUserId,
            guestDeviceId = guestDeviceId,
            memberTrialCovered = memberTrialCovered,
            refId = session.id,
            chargeAttemptId = chargeAttemptId,
            amount = storyCreationCost,
        ) {
            compileAndPersist(
                session,
                attributedUserId,
                request.visibility,
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

    /** 스토리 장르 태그와 일치하는 활성 로어북을 선별한다. 장르 태그가 없으면 빈 목록. */
    private fun selectLorebooksForGenres(genreTags: List<StoryCreationTag>): List<Lorebook> {
        val genres = genreTags.map { it.name }.distinct()
        if (genres.isEmpty()) {
            return emptyList()
        }
        return lorebookRepository.findByGenreInAndIsActiveTrueOrderByGenreAscSortOrderAscIdAsc(genres)
    }

    /** 회원 선차감. 잔액 부족([InsufficientCreditException])은 동기 402로 변환한다. */
    private fun chargeStoryCreation(userId: Long, refId: Long, amount: Long) {
        try {
            creditWalletService.deduct(
                userId = userId,
                amount = amount,
                reason = CreditReason.STORY_CREATION,
                refType = STORY_CREDIT_REF_TYPE,
                refId = refId,
            )
        } catch (exception: InsufficientCreditException) {
            // 게스트 체험 한도(GUEST_TRIAL_LIMIT_EXCEEDED)와 같은 402지만 바디 code로 사유를 구분한다(KNK-524).
            throw CodedResponseStatusException(
                HttpStatus.PAYMENT_REQUIRED,
                ApiErrorCodes.INSUFFICIENT_CREDIT,
                "이프가 부족합니다.",
                exception,
            )
        }
    }

    /**
     * [block]이 성공하면 선차감·게스트 예약을 유지하고, 실패(예외)하면 회원은 전액 환불, 게스트는 예약한
     * 체험 한도 카운터를 복원한 뒤 원래 예외를 다시 던진다(회원·게스트는 배타적이라 [userId]·[guestDeviceId] 중 하나만 채워짐).
     * 되돌리기가 실패해도 원래 예외를 던지고, 실패는 [StoryCreationRefunds]가 기록한다(STORY 5-3-4, KNK-2081).
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
        // 차감 때 쓴 금액. 환불은 반드시 같은 값이어야 한다(KNK-1056).
        amount: Long,
        block: () -> T,
    ): T {
        try {
            return block()
        } catch (throwable: Throwable) {
            val stage = StoryCreationRefunds.Stage.STORY_COMPLETION
            if (memberTrialCovered) {
                // 체험 잔여로 무료 처리됐으면 크레딧 환불이 아니라 회원 체험 카운터를 되돌린다(스펙 §4-3-7 B13).
                // restoreMember는 Redis 장애를 삼키고 false를 돌려주므로 그 값이 곧 성공 여부다(PAYMENT 5-3).
                userId?.let {
                    refunds.restore(throwable, stage, StoryCreationRefunds.Requester.MEMBER_TRIAL, refId) {
                        guestTrialLimitService.restoreMember(it, GuestTrialLimitService.Counter.STORY_CREATION)
                    }
                }
            } else {
                userId?.let {
                    val idempotencyKey = "refund:story:$chargeAttemptId"
                    refunds.restore(throwable, stage, StoryCreationRefunds.Requester.MEMBER_CREDIT, refId, idempotencyKey) {
                        refundStoryCreation(it, refId, idempotencyKey, amount)
                        true
                    }
                }
            }
            guestDeviceId?.let {
                refunds.restore(throwable, stage, StoryCreationRefunds.Requester.GUEST, refId) {
                    guestTrialLimitService.restore(it, GuestTrialLimitService.Counter.STORY_CREATION)
                    true
                }
            }
            throw throwable
        }
    }

    private fun refundStoryCreation(userId: Long, refId: Long, idempotencyKey: String, amount: Long) {
        creditWalletService.reward(
            userId = userId,
            amount = amount,
            reason = CreditReason.REFUND,
            idempotencyKey = idempotencyKey,
            refType = STORY_CREDIT_REF_TYPE,
            refId = refId,
        )
    }

    private fun compileAndPersist(
        session: StoryCreationSession,
        // finalize에서 이미 소유권을 반영해 확정한 귀속 사용자(익명 세션을 로그인 사용자가 claim한 경우 그 사용자). 게스트면 null.
        attributedUserId: Long?,
        visibility: StoryVisibility,
        selectedStoryline: StoryCreationStoryline,
        genreTags: List<StoryCreationTag>,
        // compile 요청에 실어 보낸 선별 로어북. 저장 성공 시 story_lorebooks에 연결한다(전달분과 저장분 일치).
        selectedLorebooks: List<Lorebook>,
        aiRequest: AiStoryCompileRequest,
        // 이 완성을 수행하는 생성 요청의 request_id. STORY_CREATED와 함께 세션에 박아, 회수 재실행이 이 요청인지 검증한다(KNK-644).
        requestId: UUID,
        // Langfuse trace 연결 식별자(KNK-751). 위 requestId(완성 단계)와 달리 creation_id는 스토리라인 단계 값이다.
        traceLink: AiTraceLink,
        // compile 진입 신호. 이 지점을 지난 뒤의 실패는 AI 내용 거절을 제외하고 실제 생성 실패다([creationOutcomeOf]).
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
            if (exception is StoryContentRejectedException) {
                throw CodedResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, ApiErrorCodes.CONTENT_REJECTED, requireNotNull(exception.message), exception,
                )
            }
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 스토리 생성 요청에 실패했습니다.", exception)
        }
        val aiResponse = recorded.result
        val protagonistName = validateCompileProtagonist(aiResponse)

        val genre = genreTags.joinToString(separator = ", ") { it.name }.ifEmpty { null }

        // 스토리 public_id를 트랜잭션 밖에서 미리 확정한다 — 인물 이미지 객체 키가 이 값을 쓰는데,
        // 업로드(네트워크 I/O)를 DB 트랜잭션 안에서 하면 커넥션을 수 초간 잡아 둔다.
        val storyPublicId = UUID.randomUUID()
        // 이름 정규화를 업로드보다 먼저 한 곳에서 끝낸다 — 저장 단계에서 걸러질 인물을 업로드하면 고아 객체가 된다.
        val normalizedAppearances = normalizeByName(aiResponse.characterAppearances) { it.name }
        val normalizedImages = normalizeByName(aiResponse.characterImages) { it.name }
        // 상한은 배열별이 아니라 **최종 인물 집합**에 건다 — 외형 5명과 이미지 5명의 이름이 전부 다른 비정상 응답에서
        // 배열별 상한만으로는 10행이 저장된다. 합집합은 외형 순서를 앞에 두고(first-wins) 이름이 겹치지 않는 이미지가 뒤를 잇는다.
        val allowedNames = limitCharacterNames(normalizedAppearances.keys + normalizedImages.keys, storyPublicId)
        val appearancesByName = normalizedAppearances.filterKeys(allowedNames::contains)
        val imagesByName = normalizedImages.filterKeys(allowedNames::contains)
        val descriptionsByName = normalizeByName(aiResponse.characterIntroductions) { it.name }
            .filterKeys(allowedNames::contains)
            .mapValues { (_, introduction) -> introduction.description }
        // 업로드와 보상 삭제가 같은 예산을 나눠 쓴다 — 이미지 단계 전체가 스토리 생성 요청을 끌고 가지 않게 한다.
        val imageBudget = ImageStageBudget.startingNow(GENERATED_IMAGE_STAGE_BUDGET)
        // 이미지 업로드는 트랜잭션 밖에서 끝내고, 성공한 URL만 트랜잭션 안에서 저장한다.
        val uploadedImages = generatedStoryImageUploader.uploadCharacterImages(storyPublicId, imagesByName, imageBudget)
        // 표지 썸네일도 같은 예산을 나눠 쓴다(KNK-1069). 실패하면 표지 URL과 노출 표지는 null이며 스토리 생성은 계속한다.
        val uploadedThumbnail = generatedStoryImageUploader.uploadThumbnailImage(storyPublicId, aiResponse.thumbnailImage, imageBudget)
        // 보상 삭제 대상은 인물·표지를 가리지 않고 "이번에 올린 객체 전부"다.
        val uploadedObjectKeys = uploadedImages.values.map { it.objectKey } + listOfNotNull(uploadedThumbnail?.objectKey)

        return generatedStoryImageUploader.runWithGeneratedImageCleanup(storyPublicId, uploadedObjectKeys, imageBudget) {
            simpleStoryCompletionWriter.persist(
                session = session,
                attributedUserId = attributedUserId,
                visibility = visibility,
                selectedStoryline = selectedStoryline,
                genreTags = genreTags,
                selectedLorebooks = selectedLorebooks,
                requestId = requestId,
                recorded = recorded,
                protagonistName = protagonistName,
                genre = genre,
                storyPublicId = storyPublicId,
                uploadedThumbnail = uploadedThumbnail,
                uploadedImages = uploadedImages,
                appearancesByName = appearancesByName,
                imagesByName = imagesByName,
                descriptionsByName = descriptionsByName,
            )
        }
    }

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
     * 저장할 인물 이름을 계약 상한([CHARACTER_MAX_COUNT])까지만 남긴다(스펙 §5-3-3: 0~5명).
     *
     * 초과는 AI 응답의 결함이지만 502로 올리지 않고 버린다(graceful). 업로드보다 먼저 걸러 두면
     * 저장되지 않을 인물의 이미지를 올려 고아 객체로 남기는 일도 없다.
     */
    private fun limitCharacterNames(names: Set<String>, storyPublicId: UUID): Set<String> {
        if (names.size <= CHARACTER_MAX_COUNT) {
            return names
        }
        structuredLogger.event(
            "character_limit_exceeded",
            "story_public_id" to storyPublicId.toString(),
            "received" to names.size,
            "kept" to CHARACTER_MAX_COUNT,
        )
        return names.take(CHARACTER_MAX_COUNT).toSet()
    }

    /** 컴파일 경로: 저장된 인물과 [featureNamesByCharacterId](character_id→특징명)로 인물 단위 AI 입력을 되싣는다. */
    private fun StoryCreationCharacter.toAiCharacter(featureNamesByCharacterId: Map<Long, List<String>>): AiCharacter =
        AiCharacter(
            name = name,
            gender = gender?.name,
            features = featureNamesByCharacterId[id].orEmpty(),
        )

    private companion object {
        // 인물 이름 컬럼(VARCHAR(100)) 초과 방어. 매칭 키라 절단 후 이름으로 이미지를 붙인다.
        const val STORY_CHARACTER_NAME_MAX_LENGTH = 100

        // 인물 수 계약 상한(스펙 §5-3-3: 0~5개). 초과분은 502로 올리지 않고 버린다 — 이미지·인물 저장이
        // 스토리 생성을 막지 않는다는 graceful 원칙을 따른다.
        const val CHARACTER_MAX_COUNT = 5

        // 생성 이미지 단계(업로드 + 실패 즉시 삭제 + 보상 삭제) 전체 시간 예산. 인물과 표지 썸네일이 **함께 쓴다**.
        // 정상 경로는 인물 5명 + 표지 1장 × 수백 ms 수준이라 30초는 여유롭게 남는다. 반대로 S3가 느리면 호출당
        // 상한(apiCallTimeout 10초)이 여러 번 누적될 수 있는데, 이 예산이 그 누적을 30초에서 끊는다.
        val GENERATED_IMAGE_STAGE_BUDGET: Duration = Duration.ofSeconds(30)

        // 크레딧 원장 소모·환불 행의 ref_type(연관 리소스 종류). 소모는 STORY 리소스를 가리킨다(스펙 §4-3-7).
        const val STORY_CREDIT_REF_TYPE = "STORY"
    }
}
