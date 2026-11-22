package com.knk.manyak.admin.analytics

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.global.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

// 회원, 전환, 품질 지표를 한 컨트롤러에 두어 OpenAPI 태그를 메서드마다 붙인다(KNK-2124).
// 회원 지표 이름과 설명은 AdminActivityHoursController와 같아야 한다.
private const val USER_METRICS = "Admin User Metrics"
private const val USER_METRICS_DESC = "관리자 회원 지표 API"
private const val CONVERSION = "Admin Conversion"
private const val CONVERSION_DESC = "관리자 전환 지표 API"
private const val QUALITY = "Admin Quality"
private const val QUALITY_DESC = "관리자 품질 지표 API"

@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
class AdminMetricsController(private val metrics: AdminMetricsService, private val reads: AdminMetricReadService, private val costs: AdminAiCostService) {
    @Tag(name = USER_METRICS, description = USER_METRICS_DESC) @GetMapping("/users/app-activation") fun appActivation(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = metrics.appActivation(query)
    @Tag(name = USER_METRICS, description = USER_METRICS_DESC) @GetMapping("/users/activity") fun activity(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = metrics.activity(query)
    @Tag(name = USER_METRICS, description = USER_METRICS_DESC) @GetMapping("/users/retention") fun retention(@ParameterObject @ModelAttribute query: RetentionQuery) = metrics.retention(query, query.cohort)
    @Tag(name = USER_METRICS, description = USER_METRICS_DESC) @GetMapping("/users/sessions") fun sessions(@ParameterObject @ModelAttribute query: SegmentRangeQuery) = metrics.sessions(query)
    @Tag(name = USER_METRICS, description = USER_METRICS_DESC) @GetMapping("/users/session-lengths") fun sessionLengths(@ParameterObject @ModelAttribute query: SegmentRangeQuery) = metrics.sessionLengths(query)
    @Tag(name = USER_METRICS, description = USER_METRICS_DESC) @GetMapping("/users/signups") fun signups(@ParameterObject @ModelAttribute query: AdminChartsRequest) = reads.signups(query)
    @Tag(name = CONVERSION, description = CONVERSION_DESC) @GetMapping("/conversion/funnels/{funnel}") fun funnel(@PathVariable funnel: String, @ParameterObject @ModelAttribute query: ConversionMetricsQuery) = metrics.funnel(funnel, query)
    @Tag(name = CONVERSION, description = CONVERSION_DESC) @GetMapping("/conversion/turn-reach") fun reach(@ParameterObject @ModelAttribute query: AdminSegmentRequest) = reads.reach(query)
    @Tag(name = QUALITY, description = QUALITY_DESC) @GetMapping("/quality/client") fun quality(@ParameterObject @ModelAttribute query: PlatformMetricsQuery) = metrics.quality(query)
    @Tag(name = QUALITY, description = QUALITY_DESC) @GetMapping("/quality/chat-options") fun options(@ParameterObject @ModelAttribute query: AdminSegmentRequest) = reads.options(query)
    @Tag(name = QUALITY, description = QUALITY_DESC) @GetMapping("/quality/ai-cost") fun aiCost(@ParameterObject @ModelAttribute query: AdminChartsRequest) = costs.aiCost(query)
    @ExceptionHandler(AdminAiCostUnavailable::class)
    fun aiCostUnavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(status = 503, code = "SERVICE_UNAVAILABLE", message = "AI 비용을 가져올 수 없습니다.", path = request.requestURI))
    @ExceptionHandler(AdminAnalyticsUnavailable::class)
    fun unavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(status = 503, code = "SERVICE_UNAVAILABLE", message = "분석 지표를 가져올 수 없습니다.", path = request.requestURI))
}
