package com.knk.manyak.feedback.controller

import com.knk.manyak.feedback.repository.FeedbackRepository
import com.knk.manyak.support.DatabaseCleaner
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import java.util.concurrent.TimeUnit

/**
 * 피드백 등록이 커밋된 뒤 비동기로 Slack webhook 이 호출되는지,
 * 그리고 발송이 실패해도 등록 자체는 성공(201)하는지 검증한다.
 *
 * webhook URL 은 [MockWebServer] 주소를 @DynamicPropertySource 로 주입한다.
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedbackSlackNotificationIntegrationTests {

    companion object {
        private val slackServer = MockWebServer()

        @JvmStatic
        @BeforeAll
        fun startServer() {
            slackServer.start()
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            slackServer.shutdown()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerWebhookUrl(registry: DynamicPropertyRegistry) {
            registry.add("manyak.slack.feedback-webhook-url") { slackServer.url("/hook").toString() }
        }
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `피드백을 등록하면 커밋 후 Slack 알림이 발송된다`() {
        slackServer.enqueue(MockResponse().setResponseCode(200))

        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"알림 테스트 본문","platform":"IOS","appVersion":"1.0.0"}""")
            .exchange()
            .expectStatus().isCreated

        val recorded = requireNotNull(slackServer.takeRequest(3, TimeUnit.SECONDS)) {
            "커밋 후 비동기로 Slack webhook 이 호출되어야 한다"
        }
        assertThat(recorded.path).isEqualTo("/hook")
        assertThat(recorded.body.readUtf8()).contains("알림 테스트 본문")
    }

    @Test
    fun `Slack 발송이 실패해도 피드백은 201로 저장된다`() {
        slackServer.enqueue(MockResponse().setResponseCode(500))

        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"발송 실패해도 저장되어야 함"}""")
            .exchange()
            .expectStatus().isCreated

        assertThat(feedbackRepository.count()).isEqualTo(1)
        // 발송 시도 자체는 일어나야 한다.
        assertThat(slackServer.takeRequest(3, TimeUnit.SECONDS)).isNotNull()
    }
}
