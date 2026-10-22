package com.knk.manyak.push.controller

import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.push.dto.*
import com.knk.manyak.push.entity.PushCampaignStatus
import com.knk.manyak.push.service.AdminCampaignService
import com.knk.manyak.push.service.AdminPushTemplateService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/push")
class AdminPushController(private val campaigns: AdminCampaignService, private val templates: AdminPushTemplateService) {
    @GetMapping("/campaigns")
    fun list(@RequestParam(required = false) status: PushCampaignStatus?, @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int, @RequestParam(defaultValue = "createdAt") sort: String,
        @RequestParam(defaultValue = "desc") order: String) = campaigns.list(status, page, size, sort, order)

    @PostMapping("/campaigns") @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: AdminCampaignRequest, request: HttpServletRequest) = campaigns.create(AdminRequester.userId(request), body)

    @GetMapping("/campaigns/target-count")
    fun targetCount(@RequestParam(required = false) kind: PushKind?) = campaigns.targetCount(kind
        ?: throw org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "kind는 필수입니다."))

    @GetMapping("/campaigns/{campaignId}")
    fun detail(@PathVariable campaignId: UUID) = campaigns.detail(campaignId)

    @PostMapping("/campaigns/{campaignId}/cancel")
    fun cancel(@PathVariable campaignId: UUID, @Valid @RequestBody body: AdminPushReasonRequest, request: HttpServletRequest) =
        campaigns.cancel(AdminRequester.userId(request), campaignId, body.reason)

    @PostMapping("/campaigns/{campaignId}/retry")
    fun retry(@PathVariable campaignId: UUID, @Valid @RequestBody body: AdminPushReasonRequest, request: HttpServletRequest) =
        campaigns.retry(AdminRequester.userId(request), campaignId, body.reason)

    @GetMapping("/templates/{templateKey}")
    fun template(@PathVariable templateKey: String) = templates.get(templateKey)

    @PutMapping("/templates/{templateKey}")
    fun upsert(@PathVariable templateKey: String, @Valid @RequestBody body: AdminPushTemplateRequest, request: HttpServletRequest) =
        templates.upsert(AdminRequester.userId(request), templateKey, body)

    @DeleteMapping("/templates/{templateKey}")
    fun reset(@PathVariable templateKey: String, @Valid @RequestBody body: AdminPushReasonRequest, request: HttpServletRequest) =
        templates.reset(AdminRequester.userId(request), templateKey, body.reason)
}
