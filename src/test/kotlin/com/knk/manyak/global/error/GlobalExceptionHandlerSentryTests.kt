package com.knk.manyak.global.error

import com.knk.manyak.global.observability.ApiRequestLoggingFilter
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GlobalExceptionHandler가 5xx만 Sentry로 캡처하고 4xx는 보내지 않는지 검증한다.
 * Sentry.init의 beforeSend로 이벤트를 가로채 수집하고(전송은 차단), scope tag를 확인한다.
 */
class GlobalExceptionHandlerSentryTests {

    private val handler = GlobalExceptionHandler()
    private val captured = CopyOnWriteArrayList<SentryEvent>()

    @BeforeEach
    fun setUp() {
        captured.clear()
        Sentry.init { options ->
            options.dsn = "http://test@localhost/1"
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                captured.add(event)
                null // 수집만 하고 실제 전송은 막는다
            }
        }
    }

    @AfterEach
    fun tearDown() {
        Sentry.close()
        org.slf4j.MDC.clear()
    }

    private fun request() = MockHttpServletRequest("POST", "/api/v1/stories/simple")

    @Test
    fun `일반 예외(500)는 endpoint·method·status_code tag와 함께 캡처한다`() {
        handler.handleException(RuntimeException("boom"), request())

        assertThat(captured).hasSize(1)
        val event = captured.first()
        assertThat(event.getTag("status_code")).isEqualTo("500")
        assertThat(event.getTag("endpoint")).isEqualTo("/api/v1/stories/simple")
        assertThat(event.getTag("http_method")).isEqualTo("POST")
        assertThat(event.getTag("error_code")).isEqualTo("RuntimeException")
    }

    @Test
    fun `5xx ResponseStatusException은 캡처한다`() {
        handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 실패"),
            request(),
        )

        assertThat(captured).hasSize(1)
        assertThat(captured.first().getTag("status_code")).isEqualTo("502")
    }

    @Test
    fun `4xx NOT_FOUND는 캡처하지 않는다`() {
        handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.NOT_FOUND, "없음"),
            request(),
        )

        assertThat(captured).isEmpty()
    }

    @Test
    fun `4xx CONFLICT는 캡처하지 않는다`() {
        handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.CONFLICT, "충돌"),
            request(),
        )

        assertThat(captured).isEmpty()
    }

    @Test
    fun `요청 시작 시각이 있으면 duration_ms를 timing context에 싣는다`() {
        val request = MockHttpServletRequest("POST", "/api/v1/stories/simple")
        request.setAttribute(ApiRequestLoggingFilter.REQUEST_START_NANOS_ATTRIBUTE, System.nanoTime())

        handler.handleException(RuntimeException("boom"), request)

        val event = captured.first()
        @Suppress("UNCHECKED_CAST")
        val timing = event.contexts["timing"] as Map<String, Any>
        assertThat(timing["duration_ms"]).isNotNull()
    }

    @Test
    fun `406 HttpMediaTypeNotAcceptable은 4xx로 응답하고 Sentry로 보내지 않는다`() {
        val response = handler.handleHttpMediaTypeNotAcceptableException(
            HttpMediaTypeNotAcceptableException("No acceptable representation"),
            request(),
        )

        assertThat(response.statusCode.value()).isEqualTo(406)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `415 HttpMediaTypeNotSupported는 4xx로 응답하고 Sentry로 보내지 않는다`() {
        val response = handler.handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException("Unsupported media type"),
            request(),
        )

        assertThat(response.statusCode.value()).isEqualTo(415)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `405 HttpRequestMethodNotSupported는 4xx로 응답하고 Sentry로 보내지 않는다`() {
        val response = handler.handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException("DELETE"),
            request(),
        )

        assertThat(response.statusCode.value()).isEqualTo(405)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `405 응답은 Allow 헤더로 지원 메서드를 알린다`() {
        val response = handler.handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException("DELETE", listOf("GET", "POST")),
            request(),
        )

        assertThat(response.statusCode.value()).isEqualTo(405)
        assertThat(response.headers.allow).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST)
        assertThat(captured).isEmpty()
    }
}
