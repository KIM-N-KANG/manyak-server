package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.AdminLoginRequest
import com.knk.manyak.admin.service.AdminLoginService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/auth")
class AdminAuthController(private val service: AdminLoginService) {
    @PostMapping("/login/google")
    fun login(@Valid @RequestBody request: AdminLoginRequest) = service.login(request.idToken)
}
