package com.knk.manyak.feedback.event

import com.knk.manyak.feedback.notification.FeedbackNotifier
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 피드백 저장 트랜잭션이 커밋된 뒤, 요청 스레드와 분리해(@Async) 알림을 발송한다.
 *
 * - 저장이 롤백되면 (AFTER_COMMIT 이므로) 발송하지 않는다.
 * - 등록된 모든 [FeedbackNotifier](Slack·구글 폼 등)로 병행 발송한다.
 * - 발송 실패는 각 [FeedbackNotifier] 내부에서 흡수하므로 사용자 요청에 영향을 주지 않는다.
 */
@Component
class FeedbackEventListener(
    private val feedbackNotifiers: List<FeedbackNotifier>,
) {
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onFeedbackCreated(event: FeedbackCreatedEvent) {
        feedbackNotifiers.forEach { it.notifyCreated(event) }
    }
}
