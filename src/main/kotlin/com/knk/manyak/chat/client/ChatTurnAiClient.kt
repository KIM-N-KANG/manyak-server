package com.knk.manyak.chat.client

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
 * 채팅 턴 생성 요청. AI 계약에 맞춰 snake_case로 직렬화한다.
 *
 * 오프닝은 [start_settings]로만 전달하며 [history]에는 포함하지 않는다.
 */
data class ChatTurnAiRequest(
    val genre: String,
    val story_settings: ChatTurnStorySettings,
    val start_settings: ChatTurnStartSettings,
    val history: List<ChatHistoryMessage>,
    val user_input: String,
    val summary: String,
)

data class ChatTurnStorySettings(
    val world: String,
    val character: String,
    val user_role: String,
    val rule: String,
)

data class ChatTurnStartSettings(
    val name: String,
    val prologue: String,
    val start_situation: String,
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

/** AI completed 이벤트 결과. aiOutput 전체와 다음 행동 선택지를 담는다. */
data class ChatTurnAiResult(
    val aiOutput: String,
    val choices: List<String>,
)
