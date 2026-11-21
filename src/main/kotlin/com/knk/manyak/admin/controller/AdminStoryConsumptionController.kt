package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminStoryConsumptionRequest
import com.knk.manyak.admin.service.AdminStoryConsumptionService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Stories", description = "관리자 스토리 API")
@RestController
@RequestMapping("/api/v1/admin/stories")
class AdminStoryConsumptionController(private val service: AdminStoryConsumptionService) {
    @GetMapping("/consumption")
    fun consumption(@Valid @ModelAttribute request: AdminStoryConsumptionRequest) = service.consumption(request)
}
