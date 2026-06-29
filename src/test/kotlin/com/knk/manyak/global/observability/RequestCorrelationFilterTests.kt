package com.knk.manyak.global.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * 요청 식별자 헤더를 받아 MDC에 적재하고 응답에 request_id를 echo하는 필터 동작을 고정한다.
 * MDC 적재 검증은 필터 체인이 도는 순간의 스냅샷으로, 누수 검증은 처리 종료 후 상태로 확인한다.
 */
class RequestCorrelationFilterTests {

    private val filter = RequestCorrelationFilter(DeviceIdHasher(pepper = ""))

    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    /** 필터 체인이 실행되는 순간의 MDC 스냅샷과 응답을 함께 돌려준다. */
    private fun runFilter(request: MockHttpServletRequest): Pair<Map<String, String>, MockHttpServletResponse> {
        val response = MockHttpServletResponse()
        var mdcDuringChain: Map<String, String> = emptyMap()
        val chain = FilterChain { _, _ -> mdcDuringChain = MDC.getCopyOfContextMap() ?: emptyMap() }
        filter.doFilter(request, response, chain)
        return mdcDuringChain to response
    }

    private fun request(
        deviceId: String? = null,
        sessionId: String? = null,
        requestId: String? = null,
    ): MockHttpServletRequest = MockHttpServletRequest("GET", "/api/v1/stories/simple/tags").apply {
        deviceId?.let { addHeader("X-Manyak-Device-Id", it) }
        sessionId?.let { addHeader("X-Manyak-Session-Id", it) }
        requestId?.let { addHeader("X-Manyak-Request-Id", it) }
    }

    @Test
    fun `헤더가 있으면 request_id·session_id·device_id_hash를 MDC에 넣는다`() {
        val (mdc, _) = runFilter(request(deviceId = "anon-raw-1", sessionId = "sess-1", requestId = "req_given"))

        assertThat(mdc["request_id"]).isEqualTo("req_given")
        assertThat(mdc["session_id"]).isEqualTo("sess-1")
        assertThat(mdc["device_id_hash"]).startsWith("device_hash_")
        assertThat(mdc["device_id_hash"]).isNotEqualTo("anon-raw-1")
    }

    @Test
    fun `request_id 헤더가 없으면 req_ 접두 ID를 생성한다`() {
        val (mdc, _) = runFilter(request(deviceId = "anon-raw-1", sessionId = "sess-1"))

        assertThat(mdc["request_id"]).startsWith("req_")
        assertThat(mdc["request_id"]!!.length).isGreaterThan("req_".length)
    }

    @Test
    fun `요청에 있던 X-Manyak-Request-Id를 응답에 그대로 echo한다`() {
        val (_, response) = runFilter(request(deviceId = "a", sessionId = "s", requestId = "req_given"))

        assertThat(response.getHeader("X-Manyak-Request-Id")).isEqualTo("req_given")
    }

    @Test
    fun `request_id를 생성한 경우에도 동일 값을 응답에 echo한다`() {
        val (mdc, response) = runFilter(request(deviceId = "a", sessionId = "s"))

        assertThat(response.getHeader("X-Manyak-Request-Id")).isEqualTo(mdc["request_id"])
    }

    @Test
    fun `익명 ID 원본은 MDC에 남기지 않고 해시만 남긴다`() {
        val raw = "super-secret-anon"
        val (mdc, _) = runFilter(request(deviceId = raw, sessionId = "s"))

        assertThat(mdc["device_id_hash"]).startsWith("device_hash_")
        assertThat(mdc["device_id_hash"]).doesNotContain(raw)
    }

    @Test
    fun `요청 처리 후 MDC를 비운다 (스레드풀 누수 없음)`() {
        runFilter(request(deviceId = "a", sessionId = "s", requestId = "req_x"))

        val after = MDC.getCopyOfContextMap()
        assertThat(after == null || after.isEmpty()).isTrue()
    }

    @Test
    fun `필수 헤더(device·session)가 없으면 unknown으로 채우고 경고를 남긴다`() {
        val logger = LoggerFactory.getLogger(RequestCorrelationFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val (mdc, _) = runFilter(request(requestId = "req_x"))

            assertThat(mdc["session_id"]).isEqualTo("unknown")
            assertThat(mdc["device_id_hash"]).isEqualTo("unknown")
        } finally {
            logger.detachAppender(appender)
        }

        assertThat(appender.list).anyMatch { it.level == Level.WARN }
    }

    @Test
    fun `필수 헤더가 없어도 4xx로 막지 않고 체인을 진행한다`() {
        var chainInvoked = false
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> chainInvoked = true }

        filter.doFilter(request(), response, chain)

        assertThat(chainInvoked).isTrue()
        assertThat(response.status).isEqualTo(200)
    }
}
