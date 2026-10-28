package com.knk.manyak.chat.client

import com.knk.manyak.chat.config.ChatCompactSettings
import org.slf4j.MDC
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.knk.manyak.global.observability.AiTraceLink
import com.knk.manyak.global.observability.MdcKeys
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RestChatCompactAiClientTests {
    private lateinit var server: MockWebServer
    @BeforeEach fun start() { server = MockWebServer(); server.start() }
    @AfterEach fun stop() { server.shutdown(); MDC.clear() }
    private fun client(timeout: Long = 300) = RestChatCompactAiClient(WebClient.builder().baseUrl(server.url("/").toString()).build(), ChatCompactSettings(true, 10, timeout), "/custom-compact")
    private val request = ChatCompactAiRequest("이전 요약", listOf(ChatHistoryMessage(ChatMessageRole.USER, "입력 <image>url</image>")))
    @Test fun `설정 경로와 원문 JSON 및 상관 헤더를 보내고 meta 없는 응답을 읽는다`() {
        MDC.put(MdcKeys.REQUEST_ID, "request-test")
        MDC.put(MdcKeys.SESSION_ID, "session-test")
        MDC.put(MdcKeys.DEVICE_ID_HASH, "hash-test")
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"summary":"새 요약","future_field":"ignored"}"""))
        val trace = AiTraceLink(creationId = UUID.randomUUID(), storyId = UUID.randomUUID(), chatId = UUID.randomUUID(), startSettingId = UUID.randomUUID(), turnNumber = 20)
        assertThat(client().compact(request, trace)).isEqualTo("새 요약")
        val sent = server.takeRequest()
        assertThat(sent.path).isEqualTo("/custom-compact")
        assertThat(sent.getHeader("X-Manyak-Request-Id")).isEqualTo("request-test")
        assertThat(sent.getHeader("X-Manyak-Session-Id")).isEqualTo("session-test")
        assertThat(sent.getHeader("X-Manyak-Device-Id-Hash")).isEqualTo("hash-test")
        trace.toHeaders().forEach { (key, value) -> assertThat(sent.getHeader(key)).isEqualTo(value) }
        assertThat(sent.getHeader("X-Manyak-Is-Regenerated")).isNull()
        val json = JsonMapper().readTree(sent.body.readUtf8())
        assertThat(json.path("summary").asString()).isEqualTo("이전 요약")
        assertThat(json.path("history").get(0).path("content").asString()).isEqualTo("입력 <image>url</image>")
    }
    @Test fun `응답 대기 한도를 초과하면 요청을 취소하고 실패한다`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"summary":"늦은 응답"}""").setBodyDelay(2, TimeUnit.SECONDS))
        assertThatThrownBy { client(1).compact(request, AiTraceLink()) }.isInstanceOf(IllegalStateException::class.java)
    }
    @Test fun `HTTP 오류와 연결 종료는 실패한다`() {
        server.enqueue(MockResponse().setResponseCode(422))
        assertThatThrownBy { client().compact(request, AiTraceLink()) }.isInstanceOf(Exception::class.java)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThatThrownBy { client(1).compact(request, AiTraceLink()) }.isInstanceOf(Exception::class.java)
    }
}
