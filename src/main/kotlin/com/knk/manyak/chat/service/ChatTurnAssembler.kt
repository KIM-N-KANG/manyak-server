package com.knk.manyak.chat.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.client.ChatCharacterImage
import com.knk.manyak.chat.client.ChatTurnAiRequest
import com.knk.manyak.chat.client.ChatTurnEnding
import com.knk.manyak.chat.client.ChatTurnMainEvent
import com.knk.manyak.chat.client.ChatTurnStartSettings
import com.knk.manyak.chat.client.ChatTurnStorySettings
import com.knk.manyak.chat.client.ChatTurnTargetMainEvent
import com.knk.manyak.chat.config.ChatCompactSettings
import com.knk.manyak.chat.entity.StoryChat
import com.knk.manyak.chat.entity.StoryMessage
import com.knk.manyak.chat.repository.StoryChatMainEventRepository
import com.knk.manyak.chat.repository.StoryChatSummaryRepository
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.story.service.StoryPlayReader
import com.knk.manyak.story.service.StoryPublicSnapshotService
import com.knk.manyak.story.service.UsernameTokenRenderer.render
import com.knk.manyak.story.snapshot.EndingSnapshot
import com.knk.manyak.story.snapshot.MainEventSnapshot
import com.knk.manyak.story.snapshot.StartSettingSnapshot
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager

/**
 * AI 채팅 턴 요청을 조립한다(KNK-2055에서 [ChatService]에서 분리). 이어쓰기·재생성·선택지 생성이 이 조립 하나를 공유한다.
 *
 * **`@Transactional`을 붙이지 않는다.** 조립은 [ChatService]의 진입점이 [assemblyTransactionTemplate]으로 연
 * 조립 구간 트랜잭션(읽기 전용·REPEATABLE_READ) 안에서만 불린다. 게이트(스토리)와 자식 행을 한 스냅샷에서 읽어야
 * 하기 때문이다(KNK-1059·1064). 여기서 트랜잭션 경계를 새로 정의하면 그 전제가 두 곳으로 갈라진다.
 */
@Component
class ChatTurnAssembler(
    private val summaries: StoryChatSummaryRepository,
    private val compactSettings: ChatCompactSettings,
    private val storyPlayReader: StoryPlayReader,
    // 조립 재료를 현재 값·스냅샷 어느 쪽에서 오든 같은 모양으로 뜨는 몫(KNK-1065).
    private val storyPublicSnapshotService: StoryPublicSnapshotService,
    // 채팅이 완결한 주요 사건의 조인 행 조회용(occurred_main_event_names 재료, resolveOccurredMainEventNames).
    private val storyChatMainEventRepository: StoryChatMainEventRepository,
    // 채팅 모델과 가격은 요청 조립 시 한 번 읽어 AI 호출과 차감에서 함께 사용한다.
    private val chatModelService: ChatModelService,
    private val userRepository: UserRepository,
    transactionManager: PlatformTransactionManager,
) {

    /**
     * AI 턴 요청 **조립 구간 전용** 트랜잭션(KNK-1064 Codex P2).
     *
     * 턴 경로(이어쓰기·재생성·선택지)에는 트랜잭션이 없다 — AI 호출·스트리밍이 수십 초 커넥션을 붙드는 것을
     * 피하려는 의도된 설계다. 그래서 메서드에 `@Transactional`을 붙이는 대신 조립 구간만 이 템플릿으로 짧게 묶는다.
     * **조립이 끝나면 즉시 커밋되고 그 뒤에 AI를 부른다** — 트랜잭션이 AI 호출·스트리밍으로 넘어가지 않는다.
     *
     * 인스턴스는 이것 하나다. [ChatService]의 세 진입점이 이 템플릿으로 바깥 트랜잭션을 열고, [buildAiRequest]
     * 안쪽도 같은 템플릿으로 감싸 기본 전파(REQUIRED)로 그 트랜잭션에 합류한다.
     */
    internal val assemblyTransactionTemplate = ChatService.assemblyTransactionTemplate(transactionManager)

    /**
     * 이미 검증된 채팅으로 AI 채팅 턴 요청 재료를 조립한다.
     * 오프닝은 [ChatTurnStartSettings]로만 전달하고 history에는 포함하지 않으며,
     * 현재 입력은 아직 저장 전이므로 history에 들어가지 않는다.
     */
    internal fun assembleAiRequest(
        chat: StoryChat,
        messages: List<StoryMessage>,
        userInput: String,
        turnNumber: Int,
        userSource: String?,
    ): AiTurnCall =
        buildAiRequest(
            chat = chat,
            messages = messages,
            userInput = userInput,
            turnNumber = turnNumber,
            isRegenerated = false,
            userSource = userSource,
        )

    /**
     * 같은 스냅샷의 요약과 원문 범위를 스토리 조립 재료·[userInput]과 함께 AI 요청으로 구성한다.
     * 이어쓰기는 완료 턴까지, 재생성·선택지는 대상 턴 앞까지 원문을 싣는다.
     *
     * AI 호출 헤더로 나갈 연결 식별자([AiTraceLink], KNK-751)도 이 안에서 함께 만든다 — 조립과 같은 트랜잭션에서
     * 읽어야 게이트와 다른 시점을 보지 않기 때문이다.
     */
    internal fun buildAiRequest(
        chat: StoryChat,
        // 채팅의 숨기지 않은 메시지 전체(order 오름차순). 호출부가 **이 조립 트랜잭션 안에서** 읽어 마지막 턴 검증과
        // 함께 쓴다(KNK-2074). 같은 스냅샷이라 여기서 다시 읽은 것과 같다. 트랜잭션 밖에서 읽은 목록을 넘기지 않는다.
        messages: List<StoryMessage>,
        userInput: String,
        // 이번 호출이 만들 턴 번호의 **예측치**다(이어쓰기 current_turn + 1, 재생성·선택지는 그 턴 자체인 current_turn).
        // 권위값이 아니며 최종 대조는 ai_call_logs.turn_number가 한다(번호 선점은 하지 않는다 — 실패가 번호를 태우면 안 됨).
        turnNumber: Int,
        isRegenerated: Boolean,
        userSource: String? = null,
    ): AiTurnCall = assemblyTransactionTemplate.execute {
        // 스토리를 여기서 **다시** 읽는다. 호출부가 읽어둔 값을 쓰면 게이트 판정만 트랜잭션 밖 시점이 되어
        // 자식 데이터와 다른 스냅샷을 보게 된다(Codex P2). 조립에 필요한 스토리 파생 값을 전부 이 안에서 읽는다.
        val context = ChatHistoryContext.of(chat, messages, compactSettings.enabled, turnNumber,
            summaries.findFirstByChatIdAndInvalidatedAtIsNullAndThroughTurnLessThanOrderByThroughTurnDesc(chat.id, turnNumber))
        val story = storyPlayReader.findView(chat.storyId, chat.userId)
        // 조립 재료는 생성 결과를 통해 독자에게 전달되므로 읽기 응답과 같은 규칙을 탄다(KNK-1064·1065).
        // 판정 주체는 **턴을 돌리는 사람**이고, 세 진입점(이어쓰기·재생성·선택지)이 모두 앞에서 requireChatOwner로
        // 요청자 == 채팅 소유자를 강제하므로 chat.userId가 곧 그 사람이다(게스트 채팅은 null).
        // 공개 스토리면 현재 값을 쓴다 — 제작자의 밸런스 패치가 진행 중인 채팅에도 반영돼야 한다. 비공개로
        // 되돌렸거나 삭제됐으면 마지막 공개 버전 스냅샷이다 — 화면에서 막아놓은 개작이 생성 결과로 새면 안 된다.
        //
        // 두 경우를 **같은 모양(StoryPublicSnapshot)** 으로 만들어 아래 조립이 한 갈래로 흐르게 한다. 갈래를
        // 나누면 한쪽만 고치는 순간 유출이 되살아난다 — KNK-1059가 프롤로그만 막고 설정·사건·엔딩을 놓친 방식이다.
        val showsCurrentStory = story?.currentMetadataVisible == true
        val material = if (showsCurrentStory) {
            // 이 채팅이 고른 시작 설정 하나만 읽는다(PR #224 Codex P2). 전체 캡처는 스토리의 모든 시작 설정과
            // 각 추천 입력·엔딩 본문을 뜨는데, 턴 조립은 매 턴 도는 경로이고 시작 설정 개수에 상한이 없다.
            story?.let { storyPublicSnapshotService.captureTurnMaterial(it.id, chat.startSettingId) }
        } else {
            // 스냅샷이 NULL이면(한 번도 공개된 적 없거나 V69 백필 대상 밖) 재료가 통째로 빈다. 현재 값으로
            // 되돌리지 않는다 — 그 폴백은 막으려는 유출을 정확히 그 케이스에서 되살리고, AI에는 대화 내역이
            // 함께 가므로 재료가 비는 손해가 비공개 개작을 흘리는 손해보다 작다.
            story?.let { storyPublicSnapshotService.findByStoryId(it.id) }
        }
        val startSetting = material?.startSettingOf(chat.startSettingId)
        val mainEvents = material?.mainEvents.orEmpty()
        val chatName = chat.personaNameSnapshot ?: material?.protagonistName
        val eventNames = ChatJudgmentNameMapping(mainEvents.map { it.name }, chatName)

        // 주요 사건 런타임 상태(§4-3-10, D11). AI가 무상태이므로 백엔드가 매 턴 되돌려 싣는다.
        //
        // 목표는 **라이브 id**로 저장되는데([ChatTurnPersister.applyMainEventState]) 이 목록은 스냅샷일 수
        // 있다. 스토리 수정이 자식을 delete + re-insert해 id를 갈아치우므로, id로만 비교하면 매 턴 빗나가
        // 목표와 진행 턴 수가 계속 사라진다(PR #224 Codex P2 재리뷰). id가 맞으면 그대로 쓰고(읽기 가능
        // 분기·미변경 스냅샷은 여기서 끝난다), 빗나가면 라이브 행에서 이름을 얻어 **이름으로** 맞춘다.
        val targetMainEvent = chat.targetMainEventId?.let { targetId ->
            (
                mainEvents.firstOrNull { it.id == targetId }
                    ?: storyPlayReader.findMainEventName(targetId)
                        ?.let { liveName -> mainEvents.firstOrNull { it.name == liveName } }
                )
                ?.let { ChatTurnTargetMainEvent(name = eventNames.aiName(it.name), progressTurns = chat.targetProgressTurns) }
        }
        // 요청에 싣는 그 목록 그대로를 저장 판정으로 넘긴다(PR #224 Codex P2).
        val endings = eligibleEndings(chat, startSetting, turnNumber)
        val endingNames = ChatJudgmentNameMapping(endings.map { it.name }, chatName)

        AiTurnCall(
            billingModel = chatModelService.effective(chat.modelCode),
            request = ChatTurnAiRequest(
                longResponse = chat.longResponse,
                userId = chat.userId?.let { userRepository.findById(it).orElseThrow().publicId.toString() },
                genre = material?.genre.orEmpty(),
                storySettings = ChatTurnStorySettings(
                    protagonistName = chatName.orEmpty(),
                    worldSetting = render(material?.storySettings?.worldSetting.orEmpty(), chatName),
                    characterSetting = render(material?.storySettings?.characterSetting.orEmpty(), chatName),
                    userRoleSetting = chat.personaDescriptionSnapshot ?: render(material?.storySettings?.userRoleSetting.orEmpty(), chatName),
                    ruleSetting = render(material?.storySettings?.ruleSetting.orEmpty(), chatName),
                ),
                startSettings = ChatTurnStartSettings(
                    name = render(startSetting?.name.orEmpty(), chatName),
                    // 시작 설정 참조가 끊겼으면(항목 삭제 → FK로 start_setting_id NULL) 채팅에 박아둔
                    // 프롤로그로 복구한다. 이름·시작 상황은 채팅에 없어 빈 값으로 남는 부분 복구다.
                    prologue = (
                        startSetting?.prologue?.let { render(it, chatName) }
                            ?: if (showsCurrentStory) null else brokenReferencePrologue(chat)
                        ).orEmpty(),
                    startSituation = render(startSetting?.startSituation.orEmpty(), chatName),
                ),
                history = context.history,
                userInput = userInput,
                summary = context.summary,
                // 인물-이미지 매핑(KNK-943). 이어쓰기·재생성·선택지 생성이 이 조립을 공유하므로 세 경로 모두 같은 매핑을 싣는다.
                // 스냅샷 대상이 아니다 — story_characters는 전 컬럼이 불변이고 수정 API가 손대지 않아 개작될 수 없다.
                // 인물 이미지도 나머지 재료와 같은 갈래로 흐른다(PR #273 Codex P1). 라이브 행을 직접 읽으면
                // 비공개로 되돌린 뒤의 인물 개작이 타인의 진행 중 채팅에 실린다 — 설정·사건·엔딩만 막고
                // 인물을 놓치는 것이 KNK-1059가 프롤로그만 막았던 실수와 같은 모양이다.
                characterImages = material?.characterImages.orEmpty()
                    .map { ChatCharacterImage(name = it.name, imageName = it.imageName, imageUrl = it.imageUrl) },
                userSource = userSource,
                mainEvents = mainEvents.map { ChatTurnMainEvent(eventNames.aiName(it.name), render(it.description, chatName), render(it.keySentence, chatName)) },
                targetMainEvent = targetMainEvent,
                occurredMainEventNames = resolveOccurredMainEventNames(chat, mainEvents).map(eventNames::aiName),
                endings = endings.map { ChatTurnEnding(endingNames.aiName(it.name), render(it.achievementCondition, chatName), render(it.epilogue, chatName)) },
            ),
            traceLink = AiTraceLink(
                // 간편 제작 스토리만 값이 있다(채팅 생성 시 1회 해석해 박아 둔 값). 일반 제작은 null이라 헤더가 생략된다.
                creationId = chat.creationId,
                storyId = story?.publicId,
                chatId = chat.publicId,
                // trace 연결용 식별자는 스냅샷이 아니라 실제 행에서 읽는다 — 열람자에게 가는 값이 아니라
                // Langfuse에서 이 턴이 어느 시작 설정 행을 탔는지 되짚는 관측 키다.
                startSettingId = chat.startSettingId
                    ?.let { storyPlayReader.findStartSetting(it) }
                    ?.publicId,
                turnNumber = turnNumber,
                isRegenerated = isRegenerated,
            ),
            judgmentSource = TurnJudgmentSource(endings = endings, mainEvents = mainEvents, endingNames = endingNames, eventNames = eventNames),
            protagonistName = chatName,
        )
    } ?: error("AI 턴 요청 조립이 결과 없이 끝났습니다: chatId=${chat.id}")

    /**
     * 채팅이 거쳐온(완결) 사건 이름을 주요 사건 표시 순서로 반환한다(occurred_main_event_names 재료).
     *
     * 정본은 조인 테이블(`story_chat_main_events`)이지만, 그 `main_event_id`가 `story_main_events` FK
     * **ON DELETE CASCADE**라 소유자가 주요 사건을 교체하면 행이 통째로 사라진다. 그러면 AI에게 옛 사건을
     * 후보로 보내면서 "이미 완결했다"는 사실은 못 보내, 독자가 **이미 지난 사건을 다시 겪는다**(PR #224 Codex P2).
     * 그래서 채팅에 박아둔 이름 스냅샷과 **합집합**으로 채운다.
     *
     * 합집합인 이유: 조인 행만 보면 CASCADE 삭제를 못 살리고, 이름 스냅샷만 보면 소유자가 공개 상태에서
     * 사건 이름을 바꾼 경우(조인 행은 살아 있고 새 이름이 정답)를 놓친다.
     *
     * 결과는 항상 [mainEvents](이번 요청에 실은 목록)로 걸러 그 표시 순서로 낸다 — AI가 모르는 이름을
     * "이미 완결했다"고 보내면 판정이 엉킨다.
     */
    private fun resolveOccurredMainEventNames(chat: StoryChat, mainEvents: List<MainEventSnapshot>): List<String> {
        if (mainEvents.isEmpty()) {
            return emptyList()
        }
        // 이 id 비교는 **읽기 가능 분기에서만** 기여한다. 스냅샷 분기에서는 조인 행의 라이브 id와 스냅샷 id가
        // 다른 세계라 맞지 않는데, 사건이 교체되면 FK(ON DELETE CASCADE)가 조인 행을 함께 지워 occurredIds가
        // 비고 아래 이름 합집합이 덮는다. 그래서 틀린 결과가 나오지 않는다(PR #224 Codex P2 전수 확인).
        val occurredIds = storyChatMainEventRepository.findByChatId(chat.id).map { it.mainEventId }.toSet()
        val occurredNames = mainEvents.filter { it.id in occurredIds }.map { it.name }
            .plus(chat.occurredMainEventNamesSnapshot.orEmpty())
            .toSet()
        return mainEvents.filter { it.name in occurredNames }.map { it.name }
    }

    /**
     * 이번 턴 도달 후보 엔딩만 싣는다: 이미 도달한 채팅(reached_ending_id != null)이면 빈 목록(재판정 차단),
     * 그 외엔 AI 요청의 [turnNumber]가 최소 턴 수를 충족하는 엔딩만. 최소 턴 수 판정은 백엔드 결정 몫(§4-3-10).
     *
     * [startSetting]은 조립 재료(현재 값 또는 마지막 공개 버전 스냅샷)에서 온다. 스냅샷은 활성 엔딩만 담으므로
     * 라이브 조회(`enabled = true`)와 결과가 같다([StoryPublicSnapshotService.capture]).
     */
    private fun eligibleEndings(chat: StoryChat, startSetting: StartSettingSnapshot?, turnNumber: Int): List<EndingSnapshot> {
        // 최초 1회 가드. id가 비어도(엔딩 행 교체로 id 없이 기록된 도달) 이름 스냅샷이 남으므로 둘 다 본다.
        if (chat.reachedEndingId != null || chat.reachedEndingNameSnapshot != null) {
            return emptyList()
        }
        return startSetting?.endings.orEmpty().filter { it.minTurns <= turnNumber }
    }
}

/** AI 채팅 턴 호출 한 번에 필요한 요청 본문과 연결 식별자 헤더 재료(KNK-751). */
internal data class AiTurnCall(
    val billingModel: BillingModel,
    val request: ChatTurnAiRequest,
    val traceLink: AiTraceLink,
    /**
     * 이 요청에 **실제로 실어 보낸** 엔딩·주요 사건(PR #224 Codex P2). 저장 판정이 요청과 같은 출처를
     * 보게 하려고 조립 결과에 함께 실어 [ChatTurnPersister.persistTurn]까지 흘린다. 저장 시점에 다시
     * 읽으면 조립이 스냅샷을 봤는지 현재 값을 봤는지 알 수 없어 매칭이 갈라진다.
     */
    val judgmentSource: TurnJudgmentSource,
    val protagonistName: String?,
)
