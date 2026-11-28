package com.knk.manyak.global.error

import com.knk.manyak.global.observability.ApiRequestLoggingFilter
import com.knk.manyak.global.observability.DeviceIdHasher
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.global.observability.sentry.SentryMdcEventProcessor
import jakarta.servlet.FilterChain
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
import org.springframework.mock.web.MockHttpServletResponse
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
    fun `AI 내용 거절 422는 Sentry로 보내지 않는다`() {
        handler.handleResponseStatusException(
            CodedResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "CONTENT_REJECTED", "내용 거절", IllegalStateException()),
            request(),
        )
        assertThat(captured).isEmpty()
    }

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
    fun `요청 플랫폼과 앱 버전은 500 Sentry 이벤트의 태그로 싣고 판정하지 못하면 싣지 않는다`() {
        // ACCOUNT 5-5 "요청 플랫폼 Sentry 태그". 상관관계 필터가 채운 MDC를 이벤트 프로세서가 태그로 옮긴다.
        Sentry.init { options ->
            options.dsn = "http://test@localhost/1"
            options.addEventProcessor(SentryMdcEventProcessor())
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> captured.add(event); null }
        }
        val filter = RequestCorrelationFilter(DeviceIdHasher("test-pepper"))
        val withHeaders = request().apply {
            addHeader(RequestCorrelationFilter.HEADER_PLATFORM, "ios")
            addHeader(RequestCorrelationFilter.HEADER_APP_VERSION, "1.0.0")
        }
        for (request in listOf(withHeaders, request())) {
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { req, _ ->
                handler.handleException(RuntimeException("boom"), req as jakarta.servlet.http.HttpServletRequest)
            })
        }

        assertThat(captured).hasSize(2)
        assertThat(captured[0].getTag("platform")).isEqualTo("ios")
        assertThat(captured[0].getTag("app_version")).isEqualTo("1.0.0")
        assertThat(captured[1].tags).doesNotContainKeys("platform", "app_version")
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
    fun `검색 미설정 503과 앱 코드가 있는 5xx는 그대로 캡처하고 보고 제외로 표시한 503만 빼고 같은 code로 응답한다`() {
        handler.handleResponseStatusException(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색이 설정되지 않았습니다."), request())
        handler.handleResponseStatusException(CodedResponseStatusException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "AI 실패"), request())
        val quiet = handler.handleResponseStatusException(
            CodedResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "검색을 일시적으로 사용할 수 없습니다.", reportable = false),
            request(),
        )

        assertThat(captured.map { it.getTag("status_code") }).containsExactly("503", "502")
        assertThat(quiet.statusCode.value()).isEqualTo(503)
        assertThat(quiet.body!!.code).isEqualTo("SERVICE_UNAVAILABLE")
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
