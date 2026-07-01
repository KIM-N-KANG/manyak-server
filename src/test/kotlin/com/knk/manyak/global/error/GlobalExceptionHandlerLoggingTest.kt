package com.knk.manyak.global.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException

/**
 * 4xx 예외 로그가 스택트레이스를 남기지 않는지 검증한다(KNK-289).
 * 인증 엔드포인트의 흔한 401(잘못된/만료 토큰)마다 전체 스택이 찍혀 운영 로그가 폭주하던 문제 회귀 방지.
 */
class GlobalExceptionHandlerLoggingTest {

    private val handler = GlobalExceptionHandler()
    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var originalLevel: Level? = null

    @BeforeEach
    fun setUp() {
        originalLevel = logger.level
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = originalLevel
    }

    @Test
    fun `4xx 에 cause 가 있어도 스택트레이스 없이 WARN 한 줄로 남긴다`() {
        val cause = IllegalStateException("Malformed token")

        handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰", cause),
            MockHttpServletRequest("POST", "/api/v1/auth/login/google"),
        )

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.throwableProxy).isNull() // 스택트레이스 미포함
        assertThat(event.formattedMessage).contains("IllegalStateException: Malformed token")
    }

    @Test
    fun `5xx 는 스택트레이스까지 ERROR 로 남긴다`() {
        handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류", RuntimeException("boom")),
            MockHttpServletRequest("GET", "/api/v1/x"),
        )

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.throwableProxy).isNotNull() // 5xx는 스택 유지
    }

    @Test
    fun `cause 없는 4xx 는 throwable 없이 DEBUG 로 남긴다`() {
        handler.handleResponseStatusException(
            ResponseStatusException(HttpStatus.NOT_FOUND, "없음"),
            MockHttpServletRequest("GET", "/api/v1/y"),
        )

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.DEBUG)
        assertThat(event.throwableProxy).isNull()
    }
}
