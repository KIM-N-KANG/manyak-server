package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminChartsRequest
import com.knk.manyak.admin.service.AdminChatEngagementService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin Chats", description = "관리자 채팅 API")
@RestController
@RequestMapping("/api/v1/admin/chats")
class AdminChatEngagementController(private val service: AdminChatEngagementService) {
    @GetMapping("/engagement")
    fun engagement(@Valid @ModelAttribute request: AdminChartsRequest) = service.engagement(request)
}
