package com.knk.manyak.chat.client

import com.knk.manyak.chat.config.ChatCompactSettings
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.CorrelationHeaders
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

fun interface ChatCompactAiClient {
    fun compact(request: ChatCompactAiRequest, traceLink: AiTraceLink): String
}

data class ChatCompactAiRequest(val summary: String, val history: List<ChatHistoryMessage>)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompactAiResponse(val summary: String)

@Component
@ConditionalOnProperty(name = ["manyak.ai.chat.stub"], havingValue = "true")
class StubChatCompactAiClient : ChatCompactAiClient {
    override fun compact(request: ChatCompactAiRequest, traceLink: AiTraceLink) = "대화 요약: ${request.history.size}개 메시지"
}

@Component
@ConditionalOnProperty(name = ["manyak.ai.chat.stub"], havingValue = "false", matchIfMissing = true)
class RestChatCompactAiClient(
    @Qualifier("chatTurnWebClient") private val webClient: WebClient,
    private val settings: ChatCompactSettings,
    // AI 5-CONTRACT 확정 전 임시
    @Value("\${manyak.ai.chat.compact-path:/api/v1/chat/compact}") private val path: String,
) : ChatCompactAiClient {
    override fun compact(request: ChatCompactAiRequest, traceLink: AiTraceLink): String {
        val outbound = CorrelationHeaders.forwardingHeadersFromMdc() + traceLink.toHeaders()
        return webClient.post().uri(path)
            .headers { headers -> outbound.forEach { (name, value) -> headers.set(name, value) } }
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
            .bodyValue(request).retrieve().bodyToMono(ChatCompactAiResponse::class.java)
            .block(settings.timeout)?.summary ?: error("AI compact response is empty")
    }
}
