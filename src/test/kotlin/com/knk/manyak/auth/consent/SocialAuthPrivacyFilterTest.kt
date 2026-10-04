package com.knk.manyak.auth.consent

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.spi.FilterReply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class SocialAuthPrivacyFilterTest {
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
