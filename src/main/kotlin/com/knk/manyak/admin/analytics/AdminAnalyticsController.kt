package com.knk.manyak.admin.analytics

import com.knk.manyak.global.error.ApiErrorResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/dashboard")
class AdminAnalyticsController(private val analytics: AdminAnalyticsService) {
    @GetMapping("/analytics")
    fun analytics(@RequestParam(defaultValue = "30") days: Int, request: HttpServletRequest): AdminAnalyticsResponse {
        if (request.getParameter("days")?.isBlank() == true) throw org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "days는 7, 30, 90 중 하나여야 합니다.")
        return analytics.dashboard(days)
    }

    /** 예상 가능한 외부 분석 장애만 이 경로에서 503으로 변환한다. 전역 5xx/Sentry 처리와 분리한다. */
    @ExceptionHandler(AdminAnalyticsUnavailable::class)
    fun unavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(
        status = 503, code = "SERVICE_UNAVAILABLE", message = "분석 지표를 가져올 수 없습니다.", path = request.requestURI))
}
