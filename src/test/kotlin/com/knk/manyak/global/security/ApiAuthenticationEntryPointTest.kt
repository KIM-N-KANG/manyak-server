package com.knk.manyak.global.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.JwtException
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CopyOnWriteArrayList

class ApiAuthenticationEntryPointTest {
    private val entryPoint = ApiAuthenticationEntryPoint(JsonMapper.builder().findAndAddModules().build())
    private val logger = LoggerFactory.getLogger(ApiAuthenticationEntryPoint::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var originalLevel: Level? = null
    private val captured = CopyOnWriteArrayList<SentryEvent>()
    private val sensitive = "synthetic-token synthetic-subject@example.invalid\nsynthetic-claim"

    @BeforeEach
    fun setUp() {
        originalLevel = logger.level
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
        Sentry.init { options ->
            options.dsn = "http://test@localhost/1"
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                captured.add(event)
                null
            }
        }
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = originalLevel
        Sentry.close()
    }

    @Test
    fun `원인 없는 인증 실패는 메시지와 스택 없이 DEBUG 한 줄이고 Sentry 미전송이다`() {
        val response = MockHttpServletResponse()
        entryPoint.commence(
            MockHttpServletRequest("GET", "/api/v1/auth/me"), response,
            InsufficientAuthenticationException(sensitive),
        )

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.DEBUG)
        assertThat(event.formattedMessage).contains("401", "/api/v1/auth/me", "InsufficientAuthenticationException")
        assertSafe(event, response)
    }

    @Test
    fun `인증 원인은 클래스만 WARN 한 줄로 남기고 중첩 원문과 스택 및 Sentry 전송이 없다`() {
        // JWT 검증기뿐 아니라 decoder 확장 구현의 임의 예외도 입력 원문을 포함할 수 있다.
        val causes = listOf(
            JwtException(sensitive, IllegalStateException(sensitive)),
            OAuth2AuthenticationException(OAuth2Error("invalid_token", sensitive, null)),
            IllegalArgumentException(sensitive),
        )
        for (cause in causes) {
            appender.list.clear()
            val response = MockHttpServletResponse()
            entryPoint.commence(
                MockHttpServletRequest("GET", "/api/v1/auth/me"), response,
                BadCredentialsException(sensitive, cause),
            )

            val event = appender.list.single()
            assertThat(event.level).isEqualTo(Level.WARN)
            assertThat(event.formattedMessage).contains("401", "/api/v1/auth/me", cause.javaClass.simpleName)
            assertSafe(event, response)
        }
    }

    private fun assertSafe(event: ILoggingEvent, response: MockHttpServletResponse) {
        assertThat(event.throwableProxy).isNull()
        assertThat(event.formattedMessage).doesNotContain("synthetic-token", "synthetic-subject", "synthetic-claim", "\n", "\r")
        assertThat(event.argumentArray.orEmpty().toList()).noneMatch { it is Throwable }
        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentAsString).contains("유효하지 않은 인증입니다.")
            .doesNotContain("synthetic-token", "synthetic-subject", "synthetic-claim")
        assertThat(captured).isEmpty()
    }
}
