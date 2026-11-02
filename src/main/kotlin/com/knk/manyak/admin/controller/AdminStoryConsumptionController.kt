package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminStoryConsumptionRequest
import com.knk.manyak.admin.service.AdminStoryConsumptionService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/stories")
class AdminStoryConsumptionController(private val service: AdminStoryConsumptionService) {
    @GetMapping("/consumption")
    fun consumption(@Valid @ModelAttribute request: AdminStoryConsumptionRequest) = service.consumption(request)
}
