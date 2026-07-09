package com.knk.manyak.global.observability.analytics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock

/**
 * Amplitude HTTP V2 API(`/2/httpapi`)로 서버 분석 이벤트를 발행한다(스펙 §4-7 B1).
 *
 * - **비동기·fire-and-forget**: WebClient 요청을 구독만 하고 결과를 기다리지 않아 요청 처리 지연이 없다.
 *   전송 실패는 로그로만 남기고 삼킨다(관측이 비즈니스를 깨지 않는다).
 * - **no-op 게이팅**: `manyak.analytics.amplitude.enabled=false`이거나 api-key가 비면 발행하지 않는다
 *   (로컬·테스트·키 미주입 운영에서 조용히 꺼짐).
 * - WebClient는 정적 팩토리(`WebClient.builder()`)로 만든다(기존 AI 클라이언트와 동일 관례). 이 앱은 servlet(webmvc)
 *   컨텍스트라 auto-configured `WebClient.Builder` 빈이 없으므로 주입에 의존하지 않는다.
 */
@Component
class AmplitudeAnalyticsEventPublisher(
    @param:Value("\${manyak.analytics.amplitude.enabled:false}") private val enabled: Boolean,
    @param:Value("\${manyak.analytics.amplitude.api-key:}") private val apiKey: String,
    @param:Value("\${manyak.analytics.amplitude.base-url:https://api2.amplitude.com}") baseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
) : AnalyticsEventPublisher {

    private val log = LoggerFactory.getLogger(AmplitudeAnalyticsEventPublisher::class.java)
    // 빈 값이 주입돼도(예: .env에 빈 변수로 설정) 요청 URL이 깨지지 않도록 기본 엔드포인트로 폴백한다(Codex P2).
    private val webClient = WebClient.builder().baseUrl(baseUrl.ifBlank { DEFAULT_BASE_URL }).build()

    override fun publish(event: AnalyticsEvent) {
        if (!enabled || apiKey.isBlank()) return
        try {
            webClient.post()
                .uri("/2/httpapi")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildPayload(event))
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                    { /* 성공: 응답 본문 불필요 */ },
                    { error -> log.warn("Amplitude 이벤트 전송 실패: {} ({})", event.eventType, error.toString()) },
                )
        } catch (e: Exception) {
            // 요청 조립 단계 예외도 삼킨다(발행이 호출부를 깨지 않음).
            log.warn("Amplitude 이벤트 발행 실패: {} ({})", event.eventType, e.toString())
        }
    }

    /** Amplitude HTTP V2 배치 페이로드 1건. user_id·device_id 중 최소 하나는 [ServerAnalytics]가 보장한다. */
    internal fun buildPayload(event: AnalyticsEvent): Map<String, Any?> {
        val amplitudeEvent = buildMap {
            put("event_type", event.eventType)
            event.userId?.let { put("user_id", it) }
            event.deviceId?.let { put("device_id", it) }
            event.sessionId?.let { put("session_id", it) }
            put("insert_id", event.insertId)
            put("time", clock.millis())
            put("event_properties", event.eventProperties)
        }
        return mapOf(
            "api_key" to apiKey,
            "events" to listOf(amplitudeEvent),
        )
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api2.amplitude.com"
    }
}
