package com.knk.manyak.global.observability

import com.knk.manyak.global.observability.analytics.AnalyticsDeviceContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
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
        val requestId = request.getHeader(HEADER_REQUEST_ID)?.takeIf { REQUEST_ID_PATTERN.matches(it) } ?: generateRequestId()
        val rawSessionId = request.headerOrNull(HEADER_SESSION_ID)
        val rawDeviceId = request.headerOrNull(HEADER_DEVICE_ID)

        // 내부 인증 대상 경로만 전달된 해시를 받는다. 공개 요청은 기존 원문 해시 경로를 유지한다.
        // 상관관계 필터는 인증보다 먼저 실행되므로 거절된 내부 요청도 같은 방식으로 추적한다.
        val forwardedDeviceHash = if (INTERNAL_API_MATCHER.matches(request)) {
            request.headerOrNull(CorrelationHeaders.HEADER_DEVICE_ID_HASH)?.takeIf { DEVICE_HASH_PATTERN.matches(it) }
        } else {
            null
        }

        warnIfRequiredHeadersMissing(request, rawSessionId, rawDeviceId)

        response.setHeader(HEADER_REQUEST_ID, requestId)
        MDC.put(MdcKeys.REQUEST_ID, requestId)
        MDC.put(MdcKeys.SESSION_ID, rawSessionId ?: UNKNOWN)
        MDC.put(MdcKeys.DEVICE_ID_HASH, forwardedDeviceHash ?: rawDeviceId?.let(deviceIdHasher::hash) ?: UNKNOWN)
        try {
            AnalyticsDeviceContext.withDeviceId(rawDeviceId) {
                filterChain.doFilter(request, response)
            }
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
        // 이 경고의 목적은 "프론트엔드가 실제 API 호출에서 추적 헤더를 빠뜨림"을 잡는 것이다.
        // 비즈니스 API(/api/*) 밖의 경로는 헬스체크(ALB, ECS)와 취약점 스캐너 등 기계 트래픽이라
        // 헤더가 없는 게 정상이고, 경고를 찍으면 로그의 대부분을 노이즈로 채운다(실측 92%).
        // MDC unknown 적재와 request_id 발급은 경로와 무관하게 그대로 동작한다.
        if (!request.requestURI.startsWith("/api/")) return
        // 외부 웹훅은 클라이언트 추적 헤더를 요구하지 않는다.
        if (request.requestURI.startsWith("/api/v1/webhooks/")) return

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
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        const val HEADER_DEVICE_ID = "X-Manyak-Device-Id"
        const val HEADER_SESSION_ID = "X-Manyak-Session-Id"
        const val HEADER_REQUEST_ID = "X-Manyak-Request-Id"
        const val UNKNOWN = "unknown"
        private val INTERNAL_API_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/internal/**")
        private val DEVICE_HASH_PATTERN = Regex("device_hash_[0-9a-f]{16}")
        private val log = LoggerFactory.getLogger(RequestCorrelationFilter::class.java)
    }
}
