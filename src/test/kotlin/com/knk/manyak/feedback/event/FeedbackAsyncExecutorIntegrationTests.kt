package com.knk.manyak.feedback.event

import com.knk.manyak.feedback.dto.CreateFeedbackRequest
import com.knk.manyak.feedback.notification.FeedbackNotifier
import com.knk.manyak.feedback.service.FeedbackService
import com.knk.manyak.global.observability.MdcKeys
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 피드백 저장의 커밋 후 리스너가 기본 비동기 실행기를 선택하고 요청 MDC를 전파하는지 검증한다. */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.slack.feedback-webhook-url=", "manyak.google-form.feedback.form-id="],
)
@Import(FeedbackAsyncTestConfig::class)
class FeedbackAsyncExecutorIntegrationTests {

    @Autowired
    private lateinit var feedbackService: FeedbackService

    @Autowired
    private lateinit var recordingNotifier: RecordingFeedbackNotifier

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `피드백 커밋 후 기본 async 풀에서 요청 식별자를 유지한다`() {
        MDC.put(MdcKeys.REQUEST_ID, "req_default_async_feedback")

        feedbackService.createFeedback(CreateFeedbackRequest(body = "기본 비동기 실행기 검증"))

        val observed = requireNotNull(recordingNotifier.observations.poll(5, TimeUnit.SECONDS)) {
            "피드백 커밋 후 비동기 알림이 실행되어야 한다"
        }
        assertSoftly {
            it.assertThat(observed.threadName).startsWith("async-")
            it.assertThat(observed.requestId).isEqualTo("req_default_async_feedback")
        }
    }
}

@TestConfiguration
class FeedbackAsyncTestConfig {
    @Bean
    fun recordingFeedbackNotifier() = RecordingFeedbackNotifier()
}

class RecordingFeedbackNotifier : FeedbackNotifier {
    val observations = LinkedBlockingQueue<FeedbackAsyncObservation>()

    override fun notifyCreated(event: FeedbackCreatedEvent) {
        observations.add(FeedbackAsyncObservation(Thread.currentThread().name, MDC.get(MdcKeys.REQUEST_ID)))
    }
}

data class FeedbackAsyncObservation(val threadName: String, val requestId: String?)
