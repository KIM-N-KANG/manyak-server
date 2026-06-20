package com.knk.manyak.feedback.controller

import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.repository.FeedbackRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedbackCreateControllerIntegrationTests {

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @BeforeEach
    fun setUp() {
        feedbackRepository.deleteAllInBatch()
    }

    @Test
    fun `피드백을 등록하면 201과 함께 저장된다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"진행 중 버그가 있어요.","email":"user@example.com","platform":"IOS","appVersion":"1.2.0"}""")
            .exchange()
            .expectStatus().isCreated
            // 성공 응답은 본문이 비어 있어야 한다(Unit 직렬화로 인한 {} 가 아님).
            .expectBody().isEmpty

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.body).isEqualTo("진행 중 버그가 있어요.")
        assertThat(feedback.email).isEqualTo("user@example.com")
        assertThat(feedback.platform).isEqualTo(Platform.IOS)
        assertThat(feedback.appVersion).isEqualTo("1.2.0")
        // 인증 미구현 단계라 익명 제출은 user_id 가 null 이다.
        assertThat(feedback.userId).isNull()
    }

    @Test
    fun `이메일과 메타 없이 본문만으로도 등록된다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"그냥 잘 쓰고 있어요!"}""")
            .exchange()
            .expectStatus().isCreated

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.body).isEqualTo("그냥 잘 쓰고 있어요!")
        assertThat(feedback.email).isNull()
        assertThat(feedback.platform).isNull()
        assertThat(feedback.appVersion).isNull()
    }

    @Test
    fun `이메일과 앱버전이 공백 문자열이면 null로 정규화해 저장한다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"내용은 있어요.","email":"","appVersion":"   "}""")
            .exchange()
            .expectStatus().isCreated

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.body).isEqualTo("내용은 있어요.")
        assertThat(feedback.email).isNull()
        assertThat(feedback.appVersion).isNull()
    }

    @Test
    fun `본문이 공백뿐이면 400으로 응답하고 저장하지 않는다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"   "}""")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.status").isEqualTo(400)

        assertThat(feedbackRepository.count()).isZero()
    }

    @Test
    fun `이메일 형식이 올바르지 않으면 400으로 응답한다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"내용입니다.","email":"not-an-email"}""")
            .exchange()
            .expectStatus().isBadRequest

        assertThat(feedbackRepository.count()).isZero()
    }

    @Test
    fun `허용되지 않은 플랫폼 값이면 400으로 응답한다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"내용입니다.","platform":"WINDOWS"}""")
            .exchange()
            .expectStatus().isBadRequest

        assertThat(feedbackRepository.count()).isZero()
    }

    @Test
    fun `본문이 2000자를 초과하면 400으로 응답한다`() {
        val tooLong = "가".repeat(2001)
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"$tooLong"}""")
            .exchange()
            .expectStatus().isBadRequest

        assertThat(feedbackRepository.count()).isZero()
    }
}
