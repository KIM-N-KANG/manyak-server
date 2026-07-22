package com.knk.manyak.chat.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.knk.manyak.global.observability.CorrelationHeaders
import com.knk.manyak.global.observability.aicall.AiCallMeta
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
    // 선택지 생성은 동기 REST라 전체 상한 타임아웃을 둔다. AI가 선택지 3개를 위해 내부 재호출(호출당 ~60초)을 하므로
    // 스펙의 30초(§4-3-3)는 AI가 폴백으로 200을 주기도 전에 끊는다(A11 타임아웃 역전). 재호출 1회 여유까지 감안해 90초로 둔다.
    // 백엔드 내부 stopgap(KNK-636)에서는 이 값만큼 completed가 지연되므로 무한정은 아니게 상한을 유지한다.
    @Value("\${manyak.ai.chat.choices-timeout:90s}")
    private val choicesTimeout: Duration,
) : ChatTurnAiClient {

    override fun streamTurn(
        request: ChatTurnAiRequest,
        onToken: (String) -> Unit,
    ): ChatTurnAiResult {
        var result: ChatTurnAiResult? = null

        // 리액티브 체인이 다른 스레드로 넘어가기 전에 호출 스레드의 MDC에서 상관관계 헤더를 캡처한다.
        // (구독 시점 스레드에는 MDC가 전파되지 않으므로 여기서 미리 읽어 둔다.)
        val correlationHeaders = CorrelationHeaders.forwardingHeadersFromMdc()

        webClient.post()
            .uri(CHAT_TURNS_PATH)
            .headers { headers -> correlationHeaders.forEach { (name, value) -> headers.set(name, value) } }
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
                            result = ChatTurnAiResult(
                                aiOutput = data.aiOutput,
                                choices = data.choices ?: emptyList(),
                                meta = data.meta?.toAiCallMeta(),
                                targetMainEvent = data.targetMainEvent
                                    ?.let { ChatTurnTargetMainEventResult(it.name, it.progressTurns) },
                                occurredMainEventName = data.occurredMainEventName,
                                endingName = data.endingName,
                            )
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

    override fun generateChoices(request: ChatTurnAiRequest, aiOutput: String): ChatChoicesResult {
        val correlationHeaders = CorrelationHeaders.forwardingHeadersFromMdc()
        val response = webClient.post()
            .uri(CHAT_CHOICES_PATH)
            .headers { headers -> correlationHeaders.forEach { (name, value) -> headers.set(name, value) } }
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(ChatChoicesAiRequest(request, aiOutput))
            .retrieve()
            .bodyToMono(ChoicesResponse::class.java)
            .block(choicesTimeout)
            ?: error("AI 선택지 응답이 비어 있습니다.")
        return ChatChoicesResult(
            choices = response.choices,
            meta = response.meta?.toAiCallMeta(),
        )
    }

    private fun <T> read(data: String?, type: Class<T>): T =
        objectMapper.readValue(
            data ?: error("AI SSE 이벤트에 data가 없습니다."),
            type,
        )

    /** SSE `token` 이벤트 페이로드. */
    private data class TokenData(val text: String)

    /**
     * SSE `completed` 이벤트 페이로드. 와이어 키는 camelCase(aiOutput)다.
     * choices가 누락돼도 이미 중계된 토큰·저장이 통째로 실패하지 않도록 nullable로 받아
     * 매핑 시 빈 목록으로 보정한다(Jackson은 누락 필드를 기본값이 아닌 null로 전달한다).
     * 미지 필드는 무시해, AI가 completed 페이로드를 확장해도 파싱이 깨지지 않는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CompletedData(
        val aiOutput: String,
        val choices: List<String>? = null,
        val meta: ChatCompletedMeta? = null,
        // AI 판정 결과. 와이어 키는 camelCase(targetMainEvent{name, progressTurns}·occurredMainEventName·endingName)다.
        val targetMainEvent: CompletedTargetMainEvent? = null,
        val occurredMainEventName: String? = null,
        val endingName: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CompletedTargetMainEvent(
        val name: String,
        val progressTurns: Int,
    )

    /**
     * SSE `completed` 이벤트의 호출 meta. 와이어 키는 camelCase(promptVersions 등)다.
     * promptVersions는 AI가 보낸 레이어 키→버전 맵(예: {"SAFETY":1,"CORE":2,...})을 그대로 받는다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChatCompletedMeta(
        val model: String? = null,
        val provider: String? = null,
        val inputTokenCount: Int? = null,
        val outputTokenCount: Int? = null,
        val retryCount: Int? = null,
        val promptVersions: Map<String, Int>? = null,
    ) {
        fun toAiCallMeta(): AiCallMeta = AiCallMeta(
            model = model,
            provider = provider,
            inputTokenCount = inputTokenCount,
            outputTokenCount = outputTokenCount,
            retryCount = retryCount,
            promptVersions = promptVersions,
        )
    }

    /** SSE `error` 이벤트 페이로드. */
    private data class ErrorData(val code: String, val message: String)

    /**
     * `/chat/choices` 요청 바디. 턴 재료([turn])를 그대로 평탄화(@JsonUnwrapped)해 실어 보내고 `ai_output`을 더한다.
     * 스키마는 채팅 턴 요청과 동일 + `ai_output`이다(스펙 §5-3-5). 평탄화라 turn의 필드별 snake_case 매핑이 그대로 유지된다.
     */
    private data class ChatChoicesAiRequest(
        @get:JsonUnwrapped
        val turn: ChatTurnAiRequest,
        @get:JsonProperty("ai_output")
        val aiOutput: String,
    )

    /**
     * `/chat/choices` 응답. 동기 REST라 와이어 키는 snake_case다(camelCase는 chat SSE completed만의 예외 — §5-1).
     * 미지 필드는 무시해 AI가 확장해도 파싱이 깨지지 않게 한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChoicesResponse(
        val choices: List<String>,
        val meta: ChoicesMeta? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ChoicesMeta(
        val model: String? = null,
        val provider: String? = null,
        @JsonProperty("input_token_count")
        val inputTokenCount: Int? = null,
        @JsonProperty("output_token_count")
        val outputTokenCount: Int? = null,
        @JsonProperty("retry_count")
        val retryCount: Int? = null,
        @JsonProperty("prompt_versions")
        val promptVersions: Map<String, Int>? = null,
    ) {
        fun toAiCallMeta(): AiCallMeta = AiCallMeta(
            model = model,
            provider = provider,
            inputTokenCount = inputTokenCount,
            outputTokenCount = outputTokenCount,
            retryCount = retryCount,
            promptVersions = promptVersions,
        )
    }

    private companion object {
        const val CHAT_TURNS_PATH = "/api/v1/chat/turns"
        const val CHAT_CHOICES_PATH = "/api/v1/chat/choices"
        const val EVENT_TOKEN = "token"
        const val EVENT_COMPLETED = "completed"
        const val EVENT_ERROR = "error"
        val SSE_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
