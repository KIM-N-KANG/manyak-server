package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.service.AdminModerationMetricsService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/moderation")
class AdminModerationMetricsController(private val service: AdminModerationMetricsService) {
    @GetMapping("/metrics")
    fun metrics(@Valid @ModelAttribute request: AdminChartsRequest) = service.metrics(request)
}
