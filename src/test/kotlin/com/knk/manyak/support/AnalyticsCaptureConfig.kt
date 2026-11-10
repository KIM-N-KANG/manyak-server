package com.knk.manyak.support

import com.knk.manyak.credit.controller.PaymentAnalyticsCapture
import com.knk.manyak.global.observability.analytics.AnalyticsEvent
import com.knk.manyak.global.observability.analytics.AnalyticsEventPublisher
import com.knk.manyak.global.observability.analytics.ServerAnalyticsWiringIntegrationTests.CapturingPublisher
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * 서버 분석 이벤트 캡처. 결제 테스트([PaymentAnalyticsCapture])와 배선 테스트([CapturingPublisher])가 각자 다른 조회
 * 방식의 캡처를 쓰므로 둘 다 빈으로 두고, 실제 발행기 자리에는 두 캡처로 나눠 보내는 발행기를 둔다.
 * 테스트 프로파일의 Amplitude 발행기는 키가 없어 어차피 보내지 않으므로 이 대체는 다른 테스트에 중립이다.
 * 캡처를 확인하는 테스트는 `@BeforeEach`에서 `events.clear()`로 비운다.
 */
@TestConfiguration
class AnalyticsCaptureConfig {
    @Bean fun paymentAnalyticsCapture() = PaymentAnalyticsCapture()

    @Bean fun capturingPublisher() = CapturingPublisher()

    @Bean
    @Primary
    fun capturingAnalyticsEventPublisher(payment: PaymentAnalyticsCapture, wiring: CapturingPublisher): AnalyticsEventPublisher =
        object : AnalyticsEventPublisher {
            override fun publish(event: AnalyticsEvent) {
                payment.publish(event)
                wiring.publish(event)
            }
        }
}
