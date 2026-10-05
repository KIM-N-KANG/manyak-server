package com.knk.manyak.story.submission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.test.util.ReflectionTestUtils

class SubmissionWebhookConfigTests {
    @Test fun `검수 웹훅 미설정이면 application 설정이 신고 웹훅을 알림 빈에 주입한다`() {
        val reportWebhook = "https://example.test/report-webhook"
        ApplicationContextRunner()
            .withInitializer { context ->
                // 실행 머신의 웹훅 환경변수·시스템 속성과 분리해 미설정을 재현한다.
                val sources = context.environment.propertySources
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
                YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
                    .forEach(sources::addLast)
            }
            .withPropertyValues("MANYAK_SLACK_REPORT_WEBHOOK_URL=$reportWebhook")
            .withUserConfiguration(SlackSubmissionHeldNotifier::class.java)
            .run { context ->
                assertNull(context.startupFailure)
                assertNull(context.environment.getProperty("MANYAK_SLACK_MODERATION_WEBHOOK_URL"))
                assertEquals(reportWebhook, ReflectionTestUtils.getField(context.getBean(SlackSubmissionHeldNotifier::class.java), "webhookUrl"))
            }
    }
}
