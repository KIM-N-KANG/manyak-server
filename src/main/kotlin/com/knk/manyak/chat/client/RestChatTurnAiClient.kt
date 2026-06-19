package com.knk.manyak.chat.client

import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper

/**
 * manyak-ai 채팅 턴 SSE 엔드포인트를 호출하는 실연동 클라이언트.
 *
 * `token` 청크는 가공 없이 [onToken]으로 1:1 중계하고, `completed`는 [ChatTurnAiResult]로
 * 매핑하며, AI가 내려준 `error{code,message}`는 [ChatTurnAiException]으로 변환해 백엔드가
 * 그대로 relay하게 한다. HTTP·네트워크·타임아웃 등 백엔드 자체 오류는 일반 예외로 전파해
 * 호출부(ChatService)가 자체 코드로 응답하도록 한다.
 *
 * 인터페이스 계약이 동기(blocking)이므로 SSE Flux를 [java.util.stream.Stream]으로 끌어와
 * 호출 스레드에서 순차 소비한다. `manyak.ai.chat.stub`이 false이거나 미설정일 때만 등록되어
 * [StubChatTurnAiClient]와 상호배타로 동작한다.
 */
@Component
@ConditionalOnProperty(name = ["manyak.ai.chat.stub"], havingValue = "false", matchIfMissing = true)
class RestChatTurnAiClient(
    @Qualifier("chatTurnWebClient")
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    @Value("\${manyak.ai.chat.stream-timeout:60s}")
    private val streamTimeout: Duration,
) : ChatTurnAiClient {

    override fun streamTurn(
        request: ChatTurnAiRequest,
        onToken: (String) -> Unit,
    ): ChatTurnAiResult {
        var result: ChatTurnAiResult? = null

        webClient.post()
            .uri(CHAT_TURNS_PATH)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(SSE_TYPE)
            // 이벤트 간 idle 타임아웃. AI가 멈추면 무한 대기 대신 자체 오류로 끊는다.
            .timeout(streamTimeout)
            .toStream()
            .use { stream ->
                val events = stream.iterator()
                while (events.hasNext()) {
                    val event = events.next()
                    when (event.event()) {
                        EVENT_TOKEN -> onToken(read(event.data(), TokenData::class.java).text)
                        EVENT_COMPLETED -> {
                            val data = read(event.data(), CompletedData::class.java)
                            result = ChatTurnAiResult(aiOutput = data.aiOutput, choices = data.choices)
                        }
                        EVENT_ERROR -> {
                            val data = read(event.data(), ErrorData::class.java)
                            throw ChatTurnAiException(code = data.code, message = data.message)
                        }
                    }
                }
            }

        // completed 없이 스트림이 끝난 경우는 AI relay가 아닌 백엔드 자체 오류로 본다.
        return result ?: error("completed 이벤트 없이 AI 스트림이 종료되었습니다.")
    }

    private fun <T> read(data: String?, type: Class<T>): T =
        objectMapper.readValue(
            data ?: error("AI SSE 이벤트에 data가 없습니다."),
            type,
        )

    /** SSE `token` 이벤트 페이로드. */
    private data class TokenData(val text: String)

    /** SSE `completed` 이벤트 페이로드. 와이어 키는 camelCase(aiOutput)다. */
    private data class CompletedData(val aiOutput: String, val choices: List<String>)

    /** SSE `error` 이벤트 페이로드. */
    private data class ErrorData(val code: String, val message: String)

    private companion object {
        const val CHAT_TURNS_PATH = "/api/v1/chat/turns"
        const val EVENT_TOKEN = "token"
        const val EVENT_COMPLETED = "completed"
        const val EVENT_ERROR = "error"
        val SSE_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
