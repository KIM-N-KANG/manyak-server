package com.knk.manyak.global.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 모든 API 요청의 처리 결과를 구조화 로그로 남긴다(AN-3 §4).
 * 정상 종료는 api_request_completed, 서버 실패(5xx)는 api_request_failed로 구분한다.
 *
 * 상관관계 필터([RequestCorrelationFilter], HIGHEST_PRECEDENCE) 바로 안쪽에서 실행되어
 * MDC(request_id 등)가 채워진 상태로 로깅하고, 요청 전체 구간의 소요 시간을 측정한다.
 * actuator·swagger 같은 운영 노이즈 경로는 제외한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class ApiRequestLoggingFilter(
    private val structuredLogger: StructuredLogger,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val status = response.status
            val fields = linkedMapOf<String, Any?>(
                "endpoint" to request.requestURI,
                "http_method" to request.method,
                "status_code" to status,
                "duration_ms" to durationMs,
            )
            if (status >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                fields["error_code"] = HttpStatus.resolve(status)?.name ?: "UNKNOWN"
                structuredLogger.event("api_request_failed", fields)
            } else {
                structuredLogger.event("api_request_completed", fields)
            }
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return EXCLUDED_PREFIXES.any { path.startsWith(it) } || path == "/swagger-ui.html" || path == "/error"
    }

    private companion object {
        val EXCLUDED_PREFIXES = listOf("/actuator", "/swagger-ui", "/v3/api-docs")
    }
}
