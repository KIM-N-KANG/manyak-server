package com.knk.manyak.admin.analytics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "manyak.analytics.amplitude.api-key=", "manyak.analytics.amplitude.secret-key=", "manyak.admin.play-stats.service-account-key-base64=",
])
class AdminAnalyticsUnavailableIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Test fun `측정 시작일 미설정 푸시 열람은 키 없이 200이고 관리자만 조회한다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자")); val ordinary = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        val path = "/api/v1/admin/campaigns/push-opens"
        client.get().uri(path).exchange().expectStatus().isUnauthorized
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAdminToken(ordinary.publicId)}").exchange().expectStatus().isForbidden
        client.get().uri(path).header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.asOf").isEmpty.jsonPath("$.measuredFrom").isEmpty.jsonPath("$.activityFrom").isEmpty
            .jsonPath("$.pendingDates.length()").isEqualTo(0).jsonPath("$.activityTypes.length()").isEqualTo(3)
            .jsonPath("$.serviceTypes").doesNotExist()
        for (q in listOf("days=90", "days=7&from=2026-10-20&to=2026-10-21", "from=2026-10-20")) {
            client.get().uri("$path?$q").header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}").exchange().expectStatus().isBadRequest
        }
    }

    @Test fun `키 미설정은 표준 503이며 전역 5xx ERROR와 Sentry 처리 경로에 들어가지 않는다`() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        client.get().uri("/api/v1/admin/dashboard/play-installs").header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}")
            .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
        client.get().uri("/api/v1/admin/dashboard/summary").header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}")
            .exchange().expectStatus().isOk
        val logger = LoggerFactory.getLogger(com.knk.manyak.global.error.GlobalExceptionHandler::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }; logger.addAppender(events)
        try {
            client.get().uri("/api/v1/admin/dashboard/analytics").header("Authorization", "Bearer ${jwt.issueAdminToken(admin.publicId)}")
                .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isEqualTo("분석 지표를 가져올 수 없습니다.").jsonPath("$.details.length()").isEqualTo(0)
            assertThat(events.list).isEmpty()
        } finally { logger.detachAppender(events); events.stop() }
    }
}
