package com.knk.manyak.global.observability.analytics

/**
 * 발행할 서버 분석 이벤트 1건(스펙 §6-4). 식별자는 [ServerAnalytics]가 요청 컨텍스트(MDC·인증)에서 해석해 채운다.
 *
 * - [userId]: 회원이면 public_id(UUID 문자열, §6-2). 게스트면 null.
 * - [deviceId]: 요청 헤더 X-Manyak-Device-Id 원본. 없으면 게스트는 unknown, 회원은 null.
 *   Amplitude 발송까지만 메모리에서 전달하며 로그·저장소에 기록하지 않는다.
 * - [insertId]: 재시도 중복 제거 키(`request_id:event_type`).
 * - [eventProperties]: 이벤트 고유 프로퍼티 + 공통(`is_logged_in`·`request_id`·`device_id_hash`).
 *
 * Amplitude가 이벤트를 귀속하려면 user_id·device_id 중 최소 하나가 있어야 하며, 회원=user_id·게스트=device_id로 항상 충족한다.
 */
data class AnalyticsEvent(
    val eventType: String,
    val userId: String?,
    val deviceId: String?,
    val sessionId: Long?,
    val insertId: String,
    val eventProperties: Map<String, Any?>,
) {
    // data class의 기본 toString이 원본을 로그나 예외 메시지에 노출하지 않게 한다.
    override fun toString(): String = "AnalyticsEvent(eventType=$eventType, deviceId=[REDACTED])"
}

/**
 * 서버 분석 이벤트 싱크(스펙 §4-7 B1). 발행은 **비동기·fire-and-forget**이며 어떤 경우에도 예외를 호출부로 던지지 않는다
 * (관측이 비즈니스를 깨지 않는다). 미설정(키 없음)이면 no-op이다.
 */
interface AnalyticsEventPublisher {
    fun publish(event: AnalyticsEvent)
}
