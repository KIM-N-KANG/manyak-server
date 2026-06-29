package com.knk.manyak.global.observability

import org.slf4j.MDC

/**
 * [RequestCorrelationFilter]가 MDC에 적재한 상관관계 식별자를 다운스트림(AI) 호출에 forward할
 * outbound HTTP 헤더로 변환한다. 서버와 AI의 로그·Sentry가 같은 request_id로 묶이는 토대(AN-3 §3).
 *
 * MDC에는 원본 익명 ID가 아닌 해시(device_id_hash)만 존재하므로, 원본 PII는 구조적으로 forward되지 않는다(§8).
 * 값이 없거나 필터가 채운 [RequestCorrelationFilter.UNKNOWN] sentinel이면 헤더를 생략한다
 * ("오면 붙이고 없으면 생략"). 따라서 수신 측은 헤더가 와도 되고 안 와도 되도록 동작한다.
 *
 * 요청/세션 헤더명은 인바운드와 동일한 와이어 계약이라 [RequestCorrelationFilter] 상수를 그대로 재사용한다.
 * 익명 ID는 원본 헤더(X-Manyak-Device-Id)와 구분되는 해시 전용 헤더로 내보낸다.
 */
object CorrelationHeaders {
    const val HEADER_REQUEST_ID = RequestCorrelationFilter.HEADER_REQUEST_ID
    const val HEADER_SESSION_ID = RequestCorrelationFilter.HEADER_SESSION_ID
    const val HEADER_DEVICE_ID_HASH = "X-Manyak-Device-Id-Hash"

    /** MDC 상관관계 값을 AI 호출용 forward 헤더 맵으로 변환한다. 값이 없거나 unknown이면 생략한다. */
    fun forwardingHeadersFromMdc(): Map<String, String> = buildMap {
        putIfPresent(HEADER_REQUEST_ID, MDC.get(MdcKeys.REQUEST_ID))
        putIfPresent(HEADER_SESSION_ID, MDC.get(MdcKeys.SESSION_ID))
        putIfPresent(HEADER_DEVICE_ID_HASH, MDC.get(MdcKeys.DEVICE_ID_HASH))
    }

    private fun MutableMap<String, String>.putIfPresent(name: String, value: String?) {
        if (!value.isNullOrBlank() && value != RequestCorrelationFilter.UNKNOWN) {
            put(name, value)
        }
    }
}
