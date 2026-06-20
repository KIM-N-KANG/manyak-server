package com.knk.manyak.feedback.service

import com.knk.manyak.feedback.dto.CreateFeedbackRequest
import com.knk.manyak.feedback.entity.Feedback
import com.knk.manyak.feedback.entity.Platform
import com.knk.manyak.feedback.repository.FeedbackRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository,
) {
    @Transactional
    fun createFeedback(request: CreateFeedbackRequest) {
        feedbackRepository.save(
            Feedback(
                body = request.body,
                email = request.email,
                // @Pattern 으로 이미 검증된 값이므로 안전하게 enum 으로 변환한다.
                platform = request.platform?.let { Platform.valueOf(it) },
                appVersion = request.appVersion,
            ),
        )
    }
}
