package com.knk.manyak.chat.client

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * AI 미완성 기간 동안 사용하는 스텁 클라이언트.
 *
 * 실제 AI 호출 없이 user_input 기반 응답을 멀티-char 청크로 [onToken] 발행한 뒤
 * 누적 결과를 반환한다. `manyak.ai.chat.stub=true`일 때만 빈으로 등록된다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.ai.chat.stub"], havingValue = "true")
class StubChatTurnAiClient : ChatTurnAiClient {

    override fun streamTurn(
        request: ChatTurnAiRequest,
        onToken: (String) -> Unit,
    ): ChatTurnAiResult {
        val aiOutput = buildAiOutput(request)
        aiOutput.chunked(TOKEN_CHUNK_SIZE).forEach(onToken)
        return ChatTurnAiResult(
            aiOutput = aiOutput,
            choices = buildChoices(),
        )
    }

    override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String): ChatChoicesResult =
        ChatChoicesResult(choices = buildChoices())

    private fun buildAiOutput(request: ChatTurnAiRequest): String {
        val echoed = request.userInput.trim().take(USER_INPUT_PREVIEW_LENGTH)
        return "검사장은 한순간 숨소리조차 사라진 듯 조용해졌다. " +
            "\"$echoed\" 그 한마디가 공기를 가르자 모두의 시선이 한곳으로 모였다. " +
            "이야기는 멈추지 않고 다음 장면으로 이어졌다."
    }

    private fun buildChoices(): List<String> =
        listOf(
            "주변 사람들의 반응을 살핀다.",
            "한 걸음 앞으로 나선다.",
            "조용히 자리를 벗어난다.",
        )

    private companion object {
        const val TOKEN_CHUNK_SIZE = 3
        const val USER_INPUT_PREVIEW_LENGTH = 24
    }
}
