package com.knk.manyak.user.controller

import com.knk.manyak.admin.dto.AdminOperationReason
import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.user.service.AdminSuspensionService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

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
