package com.knk.manyak.admin.analytics

import com.knk.manyak.global.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import org.springdoc.core.annotations.ParameterObject
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/campaigns")
@SecurityRequirement(name = "bearerAuth")
class CampaignAnalyticsController(private val service: CampaignAnalyticsService, private val pushOpens: PushOpensService) {
    @GetMapping("/push-opens") fun pushOpens(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = pushOpens.read(query)
    @GetMapping("/push-opens/{campaignId}") fun campaignPushOpens(@PathVariable campaignId: java.util.UUID) = pushOpens.campaign(campaignId)
    @GetMapping fun catalog() = service.catalog()
    @GetMapping("/analytics") fun analytics(@ParameterObject @ModelAttribute query: CampaignQuery) = service.analytics(query)
    @GetMapping("/funnels/creation") fun creation(@ParameterObject @ModelAttribute query: CampaignQuery) = service.creation(query)
    @GetMapping("/funnels/story-detail") fun story(@ParameterObject @ModelAttribute query: StoryCampaignQuery) = service.story(query)
    @GetMapping("/funnels/activation") fun activation(@ParameterObject @ModelAttribute query: CampaignQuery) = service.activation(query)
    @GetMapping("/reach") fun reach(@ParameterObject @ModelAttribute query: CampaignQuery) = service.reach(query)
    @GetMapping("/creatives") fun creatives(@ParameterObject @ModelAttribute query: CampaignQuery) = service.creatives(query)
    @GetMapping("/app-transfer") fun appTransfer(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = service.appTransfer(query)
    @GetMapping("/sources") fun sources(@ParameterObject @ModelAttribute query: AnalyticsRangeQuery) = service.sources(query)
    @ExceptionHandler(AdminAnalyticsUnavailable::class)
    fun unavailable(request: HttpServletRequest) = ResponseEntity.status(503).body(ApiErrorResponse(
        status = 503, code = "SERVICE_UNAVAILABLE", message = "분석 지표를 가져올 수 없습니다.", path = request.requestURI))
}
