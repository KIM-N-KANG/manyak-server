package com.knk.manyak.credit.controller

import com.knk.manyak.global.observability.analytics.AnalyticsEvent
import com.knk.manyak.global.observability.analytics.AnalyticsEventPublisher
import java.util.concurrent.CopyOnWriteArrayList

/** 결제 테스트용 분석 이벤트 캡처. 빈 등록은 공용 `AnalyticsCaptureConfig`가 한다(KNK-2053). */
class PaymentAnalyticsCapture : AnalyticsEventPublisher {
    val events = CopyOnWriteArrayList<AnalyticsEvent>()
    override fun publish(event: AnalyticsEvent) { events.add(event) }
    fun ofType(type: String) = events.filter { it.eventType == "server_${type}_processed_succeeded" }
}
