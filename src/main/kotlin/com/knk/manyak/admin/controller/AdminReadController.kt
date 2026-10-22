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
class AdminReadController(private val service: AdminReadService, private val charts: com.knk.manyak.admin.service.AdminChartsService) {
    @GetMapping("/dashboard/summary")
    fun summary() = service.summary()

    @GetMapping("/payments/orders")
    fun payments(@Valid @ModelAttribute request: AdminPaymentsRequest) = service.payments(request)

    @GetMapping("/members")
    fun members(@Valid @ModelAttribute query: AdminMembersRequest, request: HttpServletRequest) =
        service.members(AdminRequester.userId(request), query)

    @GetMapping("/members/{memberId}")
    fun member(@PathVariable memberId: UUID, request: HttpServletRequest) = service.member(AdminRequester.userId(request), memberId)

    @GetMapping("/dashboard/charts")
    fun charts(@Valid @ModelAttribute request: AdminChartsRequest) = charts.charts(request.days)

    @GetMapping("/stories")
    fun stories(@Valid @ModelAttribute request: AdminStoriesRequest) = service.stories(request)

    @GetMapping("/stories/{storyId}")
    fun story(@PathVariable storyId: UUID) = service.story(storyId)

    @GetMapping("/chats")
    fun chats(@Valid @ModelAttribute request: AdminChatsRequest) = service.chats(request)

    @GetMapping("/chats/{chatId}")
    fun chat(@PathVariable chatId: UUID) = service.chat(chatId)

    @GetMapping("/payments/orders/{orderId}")
    fun payment(@PathVariable orderId: UUID) = service.payment(orderId)

    @GetMapping("/members/{memberId}/transactions")
    fun transactions(@PathVariable memberId: UUID, @Valid @ModelAttribute query: AdminListRequest, request: HttpServletRequest) =
        service.transactions(AdminRequester.userId(request), memberId, query)

    @GetMapping("/members/{memberId}/attendance")
    fun attendance(@PathVariable memberId: UUID, @Valid @ModelAttribute query: AdminListRequest, request: HttpServletRequest) =
        service.attendance(AdminRequester.userId(request), memberId, query)

    @GetMapping("/audit-logs")
    fun auditLogs(@Valid @ModelAttribute request: AdminAuditLogsRequest) = service.auditLogs(request)
}
