package com.knk.manyak.global.observability.analytics

/**
 * 발행할 서버 분석 이벤트 1건(스펙 §6-4). 식별자는 [ServerAnalytics]가 요청 컨텍스트(MDC·인증)에서 해석해 채운다.
 *
 * - [userId]: 회원이면 public_id(UUID 문자열, §6-2). 게스트면 null.
 * - [deviceId]: 게스트 익명 키(= device_id_hash). 서버에는 원본 device_id가 없어 해시를 Amplitude device_id로 쓴다
 *   (문서화된 Phase 1 한계 — 원본 미보유라 클라이언트 device 프로필과 병합되지 않음). 회원 이벤트에도 상관용으로 함께 싣는다.
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
)

/**
 * 서버 분석 이벤트 싱크(스펙 §4-7 B1). 발행은 **비동기·fire-and-forget**이며 어떤 경우에도 예외를 호출부로 던지지 않는다
 * (관측이 비즈니스를 깨지 않는다). 미설정(키 없음)이면 no-op이다.
 */
interface AnalyticsEventPublisher {
    fun publish(event: AnalyticsEvent)
}
