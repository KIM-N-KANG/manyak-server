package com.knk.manyak.global.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 모든 요청에서 X-Manyak-* 식별자 헤더를 받아 MDC(request_id/session_id/device_id_hash)에 적재하고
 * request_id를 응답 헤더로 echo한다. 이후 모든 로그·Sentry·AI 호출이 같은 request_id로 묶이는 토대(AN-3 §3).
 *
 * 가장 높은 우선순위로 등록해 Spring Security 체인(기본 order -100)보다 먼저 실행되게 한다.
 * 그래야 인증 거부(401/403) 요청도 request_id로 추적된다.
 *
 * 필수 헤더(device/session)가 없어도 요청을 막지 않는다. 분석 신호 누락이 제품 가용성을 깨면
 * 안 되므로 unknown으로 채우고 경고만 남긴다. request_id는 없으면 생성한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter(
    private val deviceIdHasher: DeviceIdHasher,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.headerOrNull(HEADER_REQUEST_ID) ?: generateRequestId()
        val rawSessionId = request.headerOrNull(HEADER_SESSION_ID)
        val rawDeviceId = request.headerOrNull(HEADER_DEVICE_ID)

        warnIfRequiredHeadersMissing(request, rawSessionId, rawDeviceId)

        response.setHeader(HEADER_REQUEST_ID, requestId)
        MDC.put(MdcKeys.REQUEST_ID, requestId)
        MDC.put(MdcKeys.SESSION_ID, rawSessionId ?: UNKNOWN)
        MDC.put(MdcKeys.DEVICE_ID_HASH, rawDeviceId?.let(deviceIdHasher::hash) ?: UNKNOWN)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // 가장 바깥 필터로서 스레드의 MDC를 통째로 비워 스레드풀 재사용 시 값이 누수되지 않게 한다.
            MDC.clear()
        }
    }

    private fun warnIfRequiredHeadersMissing(
        request: HttpServletRequest,
        rawSessionId: String?,
        rawDeviceId: String?,
    ) {
        val missing = buildList {
            if (rawDeviceId == null) add(HEADER_DEVICE_ID)
            if (rawSessionId == null) add(HEADER_SESSION_ID)
        }
        if (missing.isNotEmpty()) {
            log.warn(
                "필수 추적 헤더 누락: missing={}, method={}, path={}",
                missing,
                request.method,
                request.requestURI,
            )
        }
    }

    private fun generateRequestId(): String = "req_" + UUID.randomUUID().toString().replace("-", "")

    private fun HttpServletRequest.headerOrNull(name: String): String? =
        getHeader(name)?.takeIf { it.isNotBlank() }

    companion object {
        const val HEADER_DEVICE_ID = "X-Manyak-Device-Id"
        const val HEADER_SESSION_ID = "X-Manyak-Session-Id"
        const val HEADER_REQUEST_ID = "X-Manyak-Request-Id"
        const val UNKNOWN = "unknown"
        private val log = LoggerFactory.getLogger(RequestCorrelationFilter::class.java)
    }
}
