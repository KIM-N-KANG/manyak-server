package com.knk.manyak.story.submission

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.image.service.UploadedImageStorage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper

class SubmissionExecutorLoggingTests {
    @Test fun `HTTP 실패는 상태 코드와 회차만 WARN으로 기록한다`() {
        verifyFailureLog(RestClientResponseException(
            "private exception https://private.test", 422, "private status", HttpHeaders(),
            "private response body".toByteArray(), Charsets.UTF_8,
        ), "422")
    }

    @Test fun `응답 없는 실패도 예외 메시지 없이 WARN으로 기록한다`() {
        verifyFailureLog(IllegalStateException("private exception https://private.test"), "null")
    }

    private fun verifyFailureLog(failure: Exception, status: String) {
        val input = JsonMapper().readTree("""{"title":"private input","thumbnailUrl":"https://private.test/image"}""")
        val transactions = Mockito.mock(SubmissionTransactions::class.java)
        val forms = Mockito.mock(SubmissionFormAssembler::class.java)
        Mockito.`when`(transactions.start(42L, 3)).thenReturn(SubmissionWork(input, emptyMap()))
        Mockito.`when`(forms.aiInput(input)).thenReturn(input)
        val images = SubmissionImages(Mockito.mock(UploadedImageStorage::class.java), forms)
        val runner = SubmissionExecutor(transactions, StoryModerationClient { throw failure }, images)
        val logger = LoggerFactory.getLogger(SubmissionExecutor::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val originalLevel = logger.level
        logger.level = Level.WARN
        logger.addAppender(appender)
        try {
            runner.run(SubmissionRequested(42L, 3))
            Mockito.verify(transactions).fail(42L, 3, "MODERATION_UNAVAILABLE")
            val event = appender.list.single()
            assertEquals(Level.WARN, event.level)
            assertEquals("moderation_execution_failed submission=42 attempt=3 error=${failure.javaClass.simpleName} status=$status", event.formattedMessage)
            assertNull(event.throwableProxy)
            assertFalse(event.formattedMessage.contains("private"))
        } finally {
            logger.detachAppender(appender)
            logger.level = originalLevel
            appender.stop()
        }
    }
}
