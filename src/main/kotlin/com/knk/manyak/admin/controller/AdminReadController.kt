package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.admin.service.AdminReadService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
class AdminReadController(private val service: AdminReadService) {
    @GetMapping("/dashboard/summary")
    fun summary() = service.summary()

    @GetMapping("/payments/orders")
    fun payments(@Valid @ModelAttribute request: AdminPaymentsRequest) = service.payments(request)

    @GetMapping("/members")
    fun members(@Valid @ModelAttribute query: AdminMembersRequest, request: HttpServletRequest) =
        service.members(AdminRequester.userId(request), query)

    @GetMapping("/members/{memberId}")
    fun member(@PathVariable memberId: UUID, request: HttpServletRequest) = service.member(AdminRequester.userId(request), memberId)

    @GetMapping("/audit-logs")
    fun auditLogs(@Valid @ModelAttribute request: AdminAuditLogsRequest) = service.auditLogs(request)
}
