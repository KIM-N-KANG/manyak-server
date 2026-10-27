package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.global.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
class AdminMetricsController(private val metrics: AdminMetricsService, private val reads: AdminMetricReadService, private val costs: AdminAiCostService) {
    @GetMapping("/users/activity") fun activity(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = metrics.activity(query)
    @GetMapping("/users/retention") fun retention(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = metrics.retention(query)
    @GetMapping("/users/sessions") fun sessions(@ParameterObject @ModelAttribute query: SegmentRangeQuery) = metrics.sessions(query)
    @GetMapping("/users/signups") fun signups(@ParameterObject @ModelAttribute query: AdminChartsRequest) = reads.signups(query)
    @GetMapping("/conversion/funnels/{funnel}") fun funnel(@PathVariable funnel: String, @ParameterObject @ModelAttribute query: ConversionMetricsQuery) = metrics.funnel(funnel, query)
    @GetMapping("/conversion/turn-reach") fun reach(@ParameterObject @ModelAttribute query: AdminSegmentRequest) = reads.reach(query)
    @GetMapping("/quality/client") fun quality(@ParameterObject @ModelAttribute query: PlatformMetricsQuery) = metrics.quality(query)
    @GetMapping("/quality/chat-options") fun options(@ParameterObject @ModelAttribute query: AdminSegmentRequest) = reads.options(query)
    @GetMapping("/quality/ai-cost") fun aiCost(@ParameterObject @ModelAttribute query: AdminChartsRequest) = costs.aiCost(query)
    @ExceptionHandler(AdminAiCostUnavailable::class)
    fun aiCostUnavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(status = 503, code = "SERVICE_UNAVAILABLE", message = "AI 비용을 가져올 수 없습니다.", path = request.requestURI))
    @ExceptionHandler(AdminAnalyticsUnavailable::class)
    fun unavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(status = 503, code = "SERVICE_UNAVAILABLE", message = "분석 지표를 가져올 수 없습니다.", path = request.requestURI))
}
