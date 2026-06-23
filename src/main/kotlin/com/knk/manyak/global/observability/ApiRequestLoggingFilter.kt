package com.knk.manyak.global.observability

import io.sentry.Sentry
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
        // GlobalExceptionHandler가 예외 캡처 시 duration_ms를 계산할 수 있도록 요청 시작 시각을 노출한다.
        request.setAttribute(REQUEST_START_NANOS_ATTRIBUTE, startNanos)
        Sentry.addBreadcrumb("request: ${request.method} ${request.requestURI}", "http")
        // 예외가 상태 설정 없이 필터 밖으로 전파되면(필터 단계 오류·Error 등) response.status가 기본 200으로 남는다.
        // 이를 잡아 5xx 실패로 분류하고 다시 던진다. (컨트롤러 예외는 GlobalExceptionHandler가 5xx로 만들어 이미 실패로 기록된다.)
        var thrown: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (throwable: Throwable) {
            thrown = throwable
            throw throwable
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val statusCode = if (thrown != null && response.status < HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                HttpStatus.INTERNAL_SERVER_ERROR.value()
            } else {
                response.status
            }
            val fields = linkedMapOf<String, Any?>(
                "endpoint" to request.requestURI,
                "http_method" to request.method,
                "status_code" to statusCode,
                "duration_ms" to durationMs,
            )
            val failure = thrown
            if (statusCode >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                fields["error_code"] = if (failure != null) {
                    failure::class.simpleName ?: "UNKNOWN"
                } else {
                    HttpStatus.resolve(statusCode)?.name ?: "UNKNOWN"
                }
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

    companion object {
        // 요청 시작 시각(nanoTime)을 담는 request attribute 키. GlobalExceptionHandler가 duration_ms 계산에 쓴다.
        const val REQUEST_START_NANOS_ATTRIBUTE = "manyak.request.start-nanos"
        private val EXCLUDED_PREFIXES = listOf("/actuator", "/swagger-ui", "/v3/api-docs")
    }
}
