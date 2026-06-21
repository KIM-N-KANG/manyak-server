package com.knk.manyak.feedback.notification

import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * [SlackFeedbackNotifier] 가 webhook 으로 피드백 내용을 전송하고,
 * URL 미설정/발송 실패 같은 예외 상황을 등록 흐름에 전파하지 않는지 검증한다.
 */
class SlackFeedbackNotifierTests {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sampleEvent() = FeedbackCreatedEvent(
        id = 42,
        body = "진행 중 버그가 있어요.",
        email = "user@example.com",
        platform = Platform.IOS,
        appVersion = "1.2.0",
        createdAt = Instant.parse("2026-06-21T00:00:00Z"),
    )

    @Test
    fun `webhook으로 피드백 내용을 담은 JSON을 POST한다`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        SlackFeedbackNotifier(server.url("/hook").toString()).notifyCreated(sampleEvent())

        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/hook")
        assertThat(recorded.getHeader("Content-Type")).contains("application/json")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("42")
        assertThat(body).contains("진행 중 버그가 있어요.")
        assertThat(body).contains("1.2.0")
    }

    @Test
    fun `webhook URL이 비어 있으면 아무 요청도 보내지 않는다`() {
        SlackFeedbackNotifier(webhookUrl = "").notifyCreated(sampleEvent())

        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `webhook이 5xx를 반환해도 예외를 던지지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(500))

        // 예외가 전파되면 이 호출 자체가 실패한다(부가 기능 실패 격리 검증).
        SlackFeedbackNotifier(
            webhookUrl = server.url("/hook").toString(),
            connectTimeout = Duration.ofSeconds(2),
            readTimeout = Duration.ofSeconds(2),
        ).notifyCreated(sampleEvent())

        // 발송 시도 자체는 일어나야 한다.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
    }
}
