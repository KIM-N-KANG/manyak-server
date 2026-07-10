package com.knk.manyak.chat.client

import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.global.observability.aicall.AiCallMeta

/**
 * 백엔드 → AI 서비스 채팅 턴 생성 계약.
 *
 * AI 서버는 토큰을 멀티-char 청크로 스트리밍한 뒤 최종 결과를 전달한다.
 * 본 인터페이스는 그 계약을 코드로 고정하며, 청크는 [onToken] 콜백으로 발행하고
 * 최종 결과를 [ChatTurnAiResult]로 반환한다.
 */
interface ChatTurnAiClient {
    /**
     * 채팅 턴을 생성한다. 생성 도중 토큰 청크가 [onToken]으로 순차 발행되고,
     * 생성이 끝나면 누적된 [ChatTurnAiResult.aiOutput]과 선택지를 반환한다.
     */
    fun streamTurn(
        request: ChatTurnAiRequest,
        onToken: (String) -> Unit,
    ): ChatTurnAiResult
}

/**
 * 채팅 턴 생성 요청. 프로퍼티명은 camelCase로 두고, AI 계약에 맞춰
 * JSON 필드명만 [JsonProperty]로 snake_case에 매핑한다.
 *
 * 오프닝은 [startSettings]로만 전달하며 [history]에는 포함하지 않는다.
 */
data class ChatTurnAiRequest(
    val genre: String,
    @JsonProperty("story_settings")
    val storySettings: ChatTurnStorySettings,
    @JsonProperty("start_settings")
    val startSettings: ChatTurnStartSettings,
    val history: List<ChatHistoryMessage>,
    @JsonProperty("user_input")
    val userInput: String,
    val summary: String,

    // 주요 사건·엔딩 런타임 재료(스펙 §5-3-4, D11). 전부 선택 필드이며, 재료가 없으면 빈 값으로 하위호환된다.
    // main_events: 스토리의 주요 사건 전체. target_main_event: 현재 목표 사건 상태(백엔드가 되돌려 실음).
    // occurred_main_event_names: 이미 완결된 사건 이름들. endings: min_turns 충족·미도달 엔딩 후보만.
    @JsonProperty("main_events")
    val mainEvents: List<ChatTurnMainEvent> = emptyList(),
    @JsonProperty("target_main_event")
    val targetMainEvent: ChatTurnTargetMainEvent? = null,
    @JsonProperty("occurred_main_event_names")
    val occurredMainEventNames: List<String> = emptyList(),
    val endings: List<ChatTurnEnding> = emptyList(),

    // 채팅 이미지 재료(스펙 §4-3-9, KNK-544). 주요 사건·엔딩과 같이 선택 필드라 AI가 아직 읽지 않아도 하위호환이다.
    // background_image_candidates: 등록 시 확정한 배경 후보. AI가 어울리는 장면에서만 1장 골라 마커로 삽입한다.
    // character_images: 컴파일이 확정한 인물↔이미지 매핑. AI는 고르지 않고 그 키를 이름표로만 쓴다.
    // 비활성 이미지는 백엔드가 전달에서 제외한다.
    @JsonProperty("background_image_candidates")
    val backgroundImageCandidates: List<ChatTurnBackgroundImage> = emptyList(),
    @JsonProperty("character_images")
    val characterImages: List<ChatTurnCharacterImage> = emptyList(),
)

/** 배경 후보 — AI가 고를 대상. 의미 태그는 카탈로그의 축을 그대로 옮긴다. */
data class ChatTurnBackgroundImage(
    @JsonProperty("image_key")
    val imageKey: String,
    val mood: String?,
    val place: String?,
    val prop: String?,
)

/** 인물↔이미지 매핑 — AI는 고르지 않고 이름표로만 쓴다(같은 인물 = 같은 이미지 보장). */
data class ChatTurnCharacterImage(
    val name: String,
    @JsonProperty("image_key")
    val imageKey: String,
)

data class ChatTurnMainEvent(
    val name: String,
    val description: String,
    @JsonProperty("key_sentence")
    val keySentence: String,
)

data class ChatTurnTargetMainEvent(
    val name: String,
    @JsonProperty("progress_turns")
    val progressTurns: Int,
)

data class ChatTurnEnding(
    val name: String,
    @JsonProperty("achievement_condition")
    val achievementCondition: String,
    val epilogue: String,
)

data class ChatTurnStorySettings(
    @JsonProperty("world_setting")
    val worldSetting: String,
    @JsonProperty("character_setting")
    val characterSetting: String,
    @JsonProperty("user_role_setting")
    val userRoleSetting: String,
    @JsonProperty("rule_setting")
    val ruleSetting: String,
)

data class ChatTurnStartSettings(
    val name: String,
    val prologue: String,
    @JsonProperty("start_situation")
    val startSituation: String,
)

/** 과거 대화 한 턴. role은 대문자(USER/ASSISTANT)로 직렬화한다. */
data class ChatHistoryMessage(
    val role: ChatMessageRole,
    val content: String,
)

enum class ChatMessageRole {
    USER,
    ASSISTANT,
}

/**
 * AI completed 이벤트 결과. aiOutput 전체와 다음 행동 선택지, 그리고 호출 meta를 담는다.
 *
 * chat meta는 completed 이벤트에 실려 오므로, client가 도메인 [AiCallMeta]로 정규화해 함께 전달한다.
 * AI가 meta를 내려주지 않으면(stub 등) null이다.
 */
data class ChatTurnAiResult(
    val aiOutput: String,
    val choices: List<String>,
    val meta: AiCallMeta? = null,

    // AI 판정 결과(스펙 §5-3-4). 백엔드가 턴 저장 트랜잭션에서 채팅 상태에 반영한다(D11).
    // targetMainEvent: 이번 턴 이후 목표 사건 상태(없거나 완결 직후는 null). occurredMainEventName: 이번 턴 완결된 사건 1건.
    // endingName: 이번 턴이 엔딩 응답이면 도달한 엔딩 이름.
    val targetMainEvent: ChatTurnTargetMainEventResult? = null,
    val occurredMainEventName: String? = null,
    val endingName: String? = null,
)

data class ChatTurnTargetMainEventResult(
    val name: String,
    val progressTurns: Int,
)
