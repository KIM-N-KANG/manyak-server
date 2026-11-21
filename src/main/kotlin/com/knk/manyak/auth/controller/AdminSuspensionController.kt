package com.knk.manyak.auth.controller

import com.knk.manyak.adminkit.dto.AdminOperationReason
import com.knk.manyak.adminkit.security.AdminRequester
import com.knk.manyak.auth.service.AdminSuspensionService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "Admin Members", description = "관리자 회원 API")
@RestController
@RequestMapping("/api/v1/admin/members/{memberId}/suspension")
class AdminSuspensionController(private val service: AdminSuspensionService) {
    @PostMapping
    fun suspend(@PathVariable memberId: UUID, @Valid @RequestBody body: AdminOperationReason, request: HttpServletRequest) =
        service.change(AdminRequester.userId(request), memberId, body.reason, suspend = true)

    @DeleteMapping
    fun unsuspend(@PathVariable memberId: UUID, @Valid @RequestBody body: AdminOperationReason, request: HttpServletRequest) =
        service.change(AdminRequester.userId(request), memberId, body.reason, suspend = false)
}
