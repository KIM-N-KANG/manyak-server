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
)
