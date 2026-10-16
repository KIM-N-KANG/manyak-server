package com.knk.manyak.credit.controller

import com.knk.manyak.global.observability.analytics.AnalyticsEvent
import com.knk.manyak.global.observability.analytics.AnalyticsEventPublisher
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

class PaymentAnalyticsCapture : AnalyticsEventPublisher {
    val events = CopyOnWriteArrayList<AnalyticsEvent>()
    override fun publish(event: AnalyticsEvent) { events.add(event) }
    fun ofType(type: String) = events.filter { it.eventType == "server_${type}_processed_succeeded" }
}

@TestConfiguration
class PaymentAnalyticsTestConfig {
    @Bean @Primary
    fun paymentAnalyticsCapture() = PaymentAnalyticsCapture()
}
