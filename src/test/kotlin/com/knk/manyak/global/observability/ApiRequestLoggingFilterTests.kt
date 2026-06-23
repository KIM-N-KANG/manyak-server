package com.knk.manyak.global.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * 모든 API 요청 종료 시 api_request_completed/failed를 자동으로 남기는 필터 동작을 고정한다.
 * 이벤트는 StructuredLogger를 통해 나가므로 그 로거에 ListAppender를 붙여 검증한다.
 */
class ApiRequestLoggingFilterTests {

    private val filter = ApiRequestLoggingFilter(StructuredLogger())
    private val logger = LoggerFactory.getLogger(StructuredLogger::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    private fun run(method: String, uri: String, responseStatus: Int): String {
        val request = MockHttpServletRequest(method, uri)
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, res -> (res as HttpServletResponse).status = responseStatus }
        filter.doFilter(request, response, chain)
        return appender.list.lastOrNull()?.formattedMessage ?: ""
    }

    @Test
    fun `정상 응답은 api_request_completed로 endpoint·method·status·duration을 남긴다`() {
        val msg = run("POST", "/api/v1/stories", 201)

        assertThat(msg).contains("event_name=api_request_completed")
        assertThat(msg).contains("http_method=POST")
        assertThat(msg).contains("endpoint=/api/v1/stories")
        assertThat(msg).contains("status_code=201")
        assertThat(msg).contains("duration_ms=")
    }

    @Test
    fun `5xx는 api_request_failed로 error_code와 함께 남긴다`() {
        val msg = run("GET", "/api/v1/stories/1", 500)

        assertThat(msg).contains("event_name=api_request_failed")
        assertThat(msg).contains("status_code=500")
        assertThat(msg).contains("error_code=INTERNAL_SERVER_ERROR")
    }

    @Test
    fun `4xx는 실패가 아니라 completed로 기록한다`() {
        val msg = run("POST", "/api/v1/feedbacks", 400)

        assertThat(msg).contains("event_name=api_request_completed")
        assertThat(msg).contains("status_code=400")
    }

    @Test
    fun `actuator 등 노이즈 경로는 로깅하지 않는다`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertThat(appender.list).isEmpty()
    }
}
