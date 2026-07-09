package com.knk.manyak.feedback.service

import com.knk.manyak.feedback.dto.CreateFeedbackRequest
import com.knk.manyak.feedback.entity.Feedback
import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.event.FeedbackCreatedEvent
import com.knk.manyak.feedback.repository.FeedbackRepository
import com.knk.manyak.global.observability.StructuredLogger
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val structuredLogger: StructuredLogger,
    private val serverAnalytics: ServerAnalytics,
) {
    @Transactional
    fun createFeedback(request: CreateFeedbackRequest, userId: Long? = null, userAgent: String? = null) {
        // 분석 이벤트를 커밋 결과에 묶는다(Codex P2): 커밋 후에만 성공, 롤백이면 실패. 저장·flush·커밋 어느 단계 실패든
        // afterCompletion이 한 번 처리하므로, 커밋 단계 실패로 인한 false success나 실패 이벤트 누락이 없다. Slack 알림도 AFTER_COMMIT이다.
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    serverAnalytics.feedbackSubmissionSucceeded(userId)
                } else {
                    serverAnalytics.feedbackSubmissionFailed(userId, AnalyticsErrorType.SERVER)
                }
            }
        })
        val feedback = feedbackRepository.save(
            Feedback(
                userId = userId,
                body = request.body,
                // 공백뿐인 선택 입력은 null 로 정규화해 데이터 일관성을 유지한다.
                email = request.email?.takeIf { it.isNotBlank() },
                // @Pattern 으로 이미 검증된 값이므로 안전하게 enum 으로 변환한다.
                platform = request.platform?.let { Platform.valueOf(it) },
                appVersion = request.appVersion?.takeIf { it.isNotBlank() },
                // 공백 헤더는 null 로 정규화하고, 컬럼 상한(512)에 맞춰 방어적으로 자른다.
                // (비인증 공개 쓰기 경로라 임의 길이 User-Agent 로 인한 저장 실패를 막는다.)
                userAgent = userAgent?.takeIf { it.isNotBlank() }?.take(512),
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
