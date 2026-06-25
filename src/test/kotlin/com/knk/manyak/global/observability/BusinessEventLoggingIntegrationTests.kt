package com.knk.manyak.global.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.knk.manyak.chat.repository.StoryPlaySessionRepository
import com.knk.manyak.feedback.repository.FeedbackRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient

/**
 * 핵심 비즈니스 이벤트(feedback·chat·story)가 실제 요청 흐름에서 구조화 로그로 남는지 검증한다.
 * 이벤트는 StructuredLogger를 통해 나가므로 그 로거에 ListAppender를 붙여 event_name·필드를 확인한다.
 * (비동기 채팅 턴 이벤트 user_message_saved/ai_response_saved는 스트리밍 경로 테스트에서 검증한다.)
 */
@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessEventLoggingIntegrationTests {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun disableSlackWebhook(registry: DynamicPropertyRegistry) {
            registry.add("manyak.slack.feedback-webhook-url") { "" }
        }
    }

    @Autowired
    private lateinit var restTestClient: RestTestClient

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var storyRepository: StoryRepository

    @Autowired
    private lateinit var storyPlaySessionRepository: StoryPlaySessionRepository

    private val logger = LoggerFactory.getLogger(StructuredLogger::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        storyPlaySessionRepository.deleteAllInBatch()
        feedbackRepository.deleteAllInBatch()
        storyRepository.deleteAllInBatch()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
    }

    private fun messagesFor(eventName: String): List<String> =
        appender.list.map { it.formattedMessage }.filter { it.contains("event_name=$eventName") }

    @Test
    fun `피드백 등록은 feedback_submitted를 content_length·has_email과 함께 남긴다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"가나다라마","email":"user@example.com"}""")
            .exchange()
            .expectStatus().isCreated

        val messages = messagesFor("feedback_submitted")
        assertThat(messages).hasSize(1)
        assertThat(messages.first()).contains("content_length=5")
        assertThat(messages.first()).contains("has_email=true")
    }

    @Test
    fun `이메일 없는 피드백은 has_email=false로 남긴다`() {
        restTestClient.post()
            .uri("/api/v1/feedbacks")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"body":"이메일 없음"}""")
            .exchange()
            .expectStatus().isCreated

        assertThat(messagesFor("feedback_submitted").first()).contains("has_email=false")
    }

    @Test
    fun `채팅 생성은 chat_started를 story_id·chat_id와 함께 남긴다`() {
        val story = storyRepository.save(Story(title = "이벤트 검증용 스토리"))

        restTestClient.post()
            .uri("/api/v1/chats")
            .contentType(MediaType.APPLICATION_JSON)
            // 채팅 생성 요청은 스토리 공개 식별자(public_id)를 받는다.
            .body("""{"storyId":"${story.publicId}"}""")
            .exchange()
            .expectStatus().isCreated

        val messages = messagesFor("chat_started")
        assertThat(messages).hasSize(1)
        // 구조화 로그의 story_id는 내부 PK(Long)를 그대로 기록한다(관측용, 외부 식별자와 무관).
        assertThat(messages.first()).contains("story_id=${story.id}")
        assertThat(messages.first()).contains("chat_id=")
    }

    @Test
    fun `스토리 생성 요청은 story_create_requested를 남기고 실패 시 story_create_failed를 error_code와 함께 남긴다`() {
        // 존재하지 않는 진행 정보 → AI 호출 전 404. 요청/실패 이벤트만 검증한다(성공 경로는 AI 스텁 필요).
        restTestClient.post()
            .uri("/api/v1/stories/simple")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"simpleCreationId":999999,"storylineId":1}""")
            .exchange()
            .expectStatus().isNotFound

        assertThat(messagesFor("story_create_requested")).hasSize(1)
        val failed = messagesFor("story_create_failed")
        assertThat(failed).hasSize(1)
        assertThat(failed.first()).contains("error_code=NOT_FOUND")
    }
}
