package com.knk.manyak.feedback.service

import com.knk.manyak.feedback.dto.CreateFeedbackRequest
import com.knk.manyak.feedback.entity.Feedback
import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import com.knk.manyak.feedback.repository.FeedbackRepository
import com.knk.manyak.global.observability.StructuredLogger
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val structuredLogger: StructuredLogger,
) {
    @Transactional
    fun createFeedback(request: CreateFeedbackRequest) {
        val feedback = feedbackRepository.save(
            Feedback(
                body = request.body,
                // 공백뿐인 선택 입력은 null 로 정규화해 데이터 일관성을 유지한다.
                email = request.email?.takeIf { it.isNotBlank() },
                // @Pattern 으로 이미 검증된 값이므로 안전하게 enum 으로 변환한다.
                platform = request.platform?.let { Platform.valueOf(it) },
                appVersion = request.appVersion?.takeIf { it.isNotBlank() },
            ),
        )
        // 저장이 커밋된 뒤 비동기로 Slack 알림을 보낸다(리스너가 AFTER_COMMIT 으로 수신).
        eventPublisher.publishEvent(
            FeedbackCreatedEvent(
                id = feedback.id,
                body = feedback.body,
                email = feedback.email,
                platform = feedback.platform,
                appVersion = feedback.appVersion,
                createdAt = feedback.createdAt,
            ),
        )
        structuredLogger.event(
            "feedback_submitted",
            "content_length" to feedback.body.length,
            "has_email" to (feedback.email != null),
        )
    }
}
