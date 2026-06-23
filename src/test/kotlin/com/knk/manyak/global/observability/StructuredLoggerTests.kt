package com.knk.manyak.global.observability

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * 구조화 이벤트 로깅 헬퍼: event_name과 임의 필드를 INFO 구조화 로그로 남긴다.
 * (MDC 공통 필드 request_id/session_id/anonymous_id_hash는 logback 인코더가 자동 부착하므로
 *  헬퍼 단위 테스트 범위 밖이다.)
 */
class StructuredLoggerTests {

    private val structuredLogger = StructuredLogger()
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

    @Test
    fun `event는 event_name과 필드를 INFO 구조화 로그로 남긴다`() {
        structuredLogger.event("story_created", "story_id" to 123, "duration_ms" to 42)

        assertThat(appender.list).hasSize(1)
        val event = appender.list.first()
        assertThat(event.level).isEqualTo(Level.INFO)
        assertThat(event.formattedMessage).contains("event_name=story_created")
        assertThat(event.formattedMessage).contains("story_id=123")
        assertThat(event.formattedMessage).contains("duration_ms=42")
    }

    @Test
    fun `필드 없이 event_name만으로도 남길 수 있다`() {
        structuredLogger.event("chat_started")

        assertThat(appender.list).hasSize(1)
        assertThat(appender.list.first().formattedMessage).contains("event_name=chat_started")
    }
}
