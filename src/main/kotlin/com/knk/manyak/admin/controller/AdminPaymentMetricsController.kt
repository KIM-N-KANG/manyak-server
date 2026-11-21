package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.service.AdminPaymentMetricsService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Payments", description = "관리자 결제 API")
@RestController
@RequestMapping("/api/v1/admin/payments")
class AdminPaymentMetricsController(private val service: AdminPaymentMetricsService) {
    @GetMapping("/metrics")
    fun metrics(@Valid @ModelAttribute request: AdminChartsRequest) = service.metrics(request)
}
