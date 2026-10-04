package com.knk.manyak.auth.consent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.spi.FilterReply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SocialAuthPrivacyFilterTest {
    @Test fun `보호 중 다른 logger와 다른 스레드의 Hibernate 로그는 통과한다`() {
        val filter = SocialAuthPrivacyFilter()
        val hibernate = LoggerFactory.getLogger("org.hibernate.orm.jdbc.error") as Logger
        val application = LoggerFactory.getLogger("com.knk.manyak.other") as Logger
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            SocialAuthPrivacyFilter.protecting {
                assertThat(filter.decide(null, application, Level.DEBUG, "visible", null, null)).isEqualTo(FilterReply.NEUTRAL)
                val other = executor.submit<FilterReply> {
                    filter.decide(null, hibernate, Level.ERROR, "visible", null, null)
                }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertThat(other).isEqualTo(FilterReply.NEUTRAL)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `Logback 배선에서 보호 중 비대상 logger와 다른 스레드 로그를 보존한다`() {
        val context = LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        val filter = SocialAuthPrivacyFilter().apply { this.context = context; start() }
        val hibernate = context.getLogger("org.hibernate.test.privacy")
        val application = context.getLogger("com.knk.manyak.test.privacy")
        val hibernateLevel = hibernate.level
        val applicationLevel = application.level
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        context.addTurboFilter(filter)
        hibernate.level = Level.DEBUG
        application.level = Level.DEBUG
        hibernate.addAppender(appender)
        application.addAppender(appender)
        try {
            SocialAuthPrivacyFilter.protecting {
                hibernate.error("hidden")
                application.debug("application-visible")
                executor.submit { hibernate.debug("thread-visible") }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            hibernate.debug("outside-visible")
            assertThat(appender.list.map { it.formattedMessage })
                .containsExactly("application-visible", "thread-visible", "outside-visible")
        } finally {
            executor.shutdownNow()
            hibernate.detachAppender(appender)
            application.detachAppender(appender)
            hibernate.level = hibernateLevel
            application.level = applicationLevel
            context.turboFilterList.remove(filter)
            filter.stop()
            appender.stop()
        }
    }

    @Test fun `새 인증 구간의 Hibernate 원시 로그를 차단하고 예외 뒤에도 범위를 복원한다`() {
        val filter = SocialAuthPrivacyFilter()
        val logger = LoggerFactory.getLogger("org.hibernate.orm.jdbc.error") as Logger
        fun decision() = filter.decide(null, logger, Level.ERROR, "private-sub", null, IllegalStateException("private-sub"))
        assertThat(decision()).isEqualTo(FilterReply.NEUTRAL)
        try {
            SocialAuthPrivacyFilter.protecting {
                assertThat(decision()).isEqualTo(FilterReply.DENY)
                SocialAuthPrivacyFilter.protecting { assertThat(decision()).isEqualTo(FilterReply.DENY) }
                assertThat(decision()).isEqualTo(FilterReply.DENY)
                throw IllegalStateException("test failure")
            }
        } catch (_: IllegalStateException) { }
        assertThat(decision()).isEqualTo(FilterReply.NEUTRAL)
    }
}
