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
                // 공백뿐인 선택 입력은 null 로 정규화해 데이터 일관성을 유지한다.
                email = request.email?.takeIf { it.isNotBlank() },
                // @Pattern 으로 이미 검증된 값이므로 안전하게 enum 으로 변환한다.
                platform = request.platform?.let { Platform.valueOf(it) },
                appVersion = request.appVersion?.takeIf { it.isNotBlank() },
            ),
        )
    }
}
