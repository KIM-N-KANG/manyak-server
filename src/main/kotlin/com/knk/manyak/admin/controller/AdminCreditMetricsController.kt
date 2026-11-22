package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.service.AdminCreditMetricsService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Credits", description = "관리자 이프 API")
@RestController
@RequestMapping("/api/v1/admin/credits")
class AdminCreditMetricsController(private val service: AdminCreditMetricsService) {
    @GetMapping("/metrics")
    fun metrics(@Valid @ModelAttribute request: AdminChartsRequest) = service.metrics(request)
}
