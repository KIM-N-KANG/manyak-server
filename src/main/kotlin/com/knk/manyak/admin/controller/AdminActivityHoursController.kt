package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.service.AdminActivityHoursService
import com.knk.manyak.global.config.OpenApiConfig
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springdoc.core.annotations.ParameterObject
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/users")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
class AdminActivityHoursController(private val service: AdminActivityHoursService) {
    @GetMapping("/activity-hours")
    fun activityHours(@Valid @ParameterObject @ModelAttribute request: AdminChartsRequest) = service.activityHours(request)
}
