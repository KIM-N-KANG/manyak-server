package com.knk.manyak.feedback.notification

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.ServerSocket
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

    @Test
    fun `발송 실패 로그에 webhook URL(secret)이 남지 않는다`() {
        // 닫힌 포트로 연결 실패를 유발하면, 예외 메시지에 요청 URL(secret 토큰 포함)이 섞일 수 있다.
        val deadPort = ServerSocket(0).use { it.localPort }
        val secretUrl = "http://localhost:$deadPort/services/T000/B000/SUPERSECRETTOKEN"

        val logger = LoggerFactory.getLogger(SlackFeedbackNotifier::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            SlackFeedbackNotifier(
                webhookUrl = secretUrl,
                connectTimeout = Duration.ofMillis(500),
                readTimeout = Duration.ofMillis(500),
            ).notifyCreated(sampleEvent())
        } finally {
            logger.detachAppender(appender)
        }

        // 실패가 로깅되더라도 메시지·스택 어디에도 webhook secret 이 남아선 안 된다.
        val logged = appender.list.joinToString("\n") { event ->
            event.formattedMessage + " " + (event.throwableProxy?.let { "${it.className}: ${it.message}" } ?: "")
        }
        assertThat(appender.list).isNotEmpty()
        assertThat(logged).doesNotContain("SUPERSECRETTOKEN")
    }
}
