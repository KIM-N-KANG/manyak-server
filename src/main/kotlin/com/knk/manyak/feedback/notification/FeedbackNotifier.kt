package com.knk.manyak.feedback.notification

import com.knk.manyak.feedback.event.FeedbackCreatedEvent

/**
 * 신규 피드백을 외부 채널로 알린다.
 *
 * 알림은 부가 기능이므로, 구현체는 발송 실패를 자체적으로 흡수하고
 * 호출자(피드백 등록 흐름)에 예외를 전파하지 않아야 한다.
 */
interface FeedbackNotifier {
    fun notifyCreated(event: FeedbackCreatedEvent)
}
