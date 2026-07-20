package com.knk.manyak.feedback.notification

import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * [GoogleFormFeedbackNotifier] 가 구글 폼 formResponse 엔드포인트로 피드백을
 * form-urlencoded POST 하고, 미설정/발송 실패를 등록 흐름에 전파하지 않는지 검증한다.
 */
class GoogleFormFeedbackNotifierTests {

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

    private fun notifier(
        formId: String = "TEST_FORM",
        baseUrl: String = server.url("").toString().removeSuffix("/"),
    ) = GoogleFormFeedbackNotifier(
        baseUrl = baseUrl,
        formId = formId,
        bodyEntryId = "1064236498",
        emailEntryId = "1084788855",
    )

    @Test
    fun `formResponse로 피드백 본문과 이메일을 form-urlencoded POST한다`() {
        server.enqueue(MockResponse().setResponseCode(200))

        notifier().notifyCreated(sampleEvent())

        val recorded = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/forms/d/e/TEST_FORM/formResponse")
        assertThat(recorded.getHeader("Content-Type")).contains("application/x-www-form-urlencoded")
        val body = URLDecoder.decode(recorded.body.readUtf8(), "UTF-8")
        assertThat(body).contains("entry.1064236498=진행 중 버그가 있어요.")
        assertThat(body).contains("entry.1084788855=user@example.com")
    }

    @Test
    fun `이메일이 없으면 이메일 필드를 전송하지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(200))

        notifier().notifyCreated(sampleEvent().copy(email = null))

        val body = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        assertThat(body).contains("entry.1064236498")
        assertThat(body).doesNotContain("entry.1084788855")
    }

    @Test
    fun `formId가 비어 있으면 아무 요청도 보내지 않는다`() {
        notifier(formId = "").notifyCreated(sampleEvent())

        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `formResponse가 5xx를 반환해도 예외를 던지지 않는다`() {
        server.enqueue(MockResponse().setResponseCode(500))

        // 예외가 전파되면 이 호출 자체가 실패한다(부가 기능 실패 격리 검증).
        notifier().notifyCreated(sampleEvent())

        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
    }
}
