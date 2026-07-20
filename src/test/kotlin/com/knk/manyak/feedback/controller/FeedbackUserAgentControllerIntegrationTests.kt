package com.knk.manyak.feedback.controller

import com.knk.manyak.feedback.repository.FeedbackRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * 피드백 등록 시 서버가 요청 User-Agent 헤더를 함께 저장하는지 검증한다(스펙 §4-3-4 Phase 1 계획).
 *
 * 헤더 미전송(→ null) 케이스를 검증하려면 실제 HTTP 클라이언트가 기본 User-Agent 를 자동 주입하지 않아야 한다.
 * RestTestClient(RANDOM_PORT)의 하부 전송(Reactor Netty)은 헤더 미설정 시 `ReactorNetty/x.y` 를 강제로 붙여
 * "헤더 없음" 을 표현할 수 없으므로, 헤더를 완전히 제어할 수 있는 in-process MockMvc 로 검증한다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class FeedbackUserAgentControllerIntegrationTests {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun disableSlackWebhook(registry: DynamicPropertyRegistry) {
            // 환경변수(MANYAK_SLACK_FEEDBACK_WEBHOOK_URL)가 export 돼 있어도 빈 값을 강제해 외부 발송을 막는다.
            registry.add("manyak.slack.feedback-webhook-url") { "" }
            // env(MANYAK_GOOGLE_FORM_FEEDBACK_ID) 오염까지 막아 실 구글 폼으로 발송하지 않도록 강제한다.
            registry.add("manyak.google-form.feedback.form-id") { "" }
        }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var databaseCleaner: DatabaseCleaner

    @BeforeEach
    fun setUp() {
        databaseCleaner.cleanAll()
    }

    @Test
    fun `요청의 User-Agent 헤더를 함께 저장한다`() {
        // platform enum 만으로는 세분화에 한계가 있어, 서버가 요청 헤더 원문을 추가 저장한다.
        val userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) ManyakApp/1.2.0"
        mockMvc.post("/api/v1/feedbacks") {
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.USER_AGENT, userAgent)
            content = """{"body":"헤더 저장 확인용 피드백입니다."}"""
        }.andExpect {
            status { isCreated() }
        }

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.userAgent).isEqualTo(userAgent)
    }

    @Test
    fun `User-Agent 헤더가 없으면 null로 저장한다`() {
        // @RequestHeader(required=false) 라 헤더 미전송 시 서버는 null 을 저장한다.
        mockMvc.post("/api/v1/feedbacks") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"body":"User-Agent 미전송 확인용 피드백입니다."}"""
        }.andExpect {
            status { isCreated() }
        }

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.userAgent).isNull()
    }

    @Test
    fun `User-Agent 헤더가 공백뿐이면 null로 정규화해 저장한다`() {
        // email·appVersion 과 동일하게 공백 입력은 null 로 정규화한다.
        mockMvc.post("/api/v1/feedbacks") {
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.USER_AGENT, "   ")
            content = """{"body":"공백 User-Agent 확인용 피드백입니다."}"""
        }.andExpect {
            status { isCreated() }
        }

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.userAgent).isNull()
    }

    @Test
    fun `User-Agent 가 컬럼 상한을 넘으면 512자로 잘라 저장한다`() {
        // 비인증 공개 쓰기 경로라 임의 길이 User-Agent 로 인한 저장 실패(500)를 막기 위해 컬럼 상한(512)에 맞춰 자른다.
        val longUserAgent = "A".repeat(600)
        mockMvc.post("/api/v1/feedbacks") {
            contentType = MediaType.APPLICATION_JSON
            header(HttpHeaders.USER_AGENT, longUserAgent)
            content = """{"body":"긴 User-Agent 확인용 피드백입니다."}"""
        }.andExpect {
            status { isCreated() }
        }

        val feedback = feedbackRepository.findAll().first()
        assertThat(feedback.userAgent).isEqualTo("A".repeat(512))
    }
}
