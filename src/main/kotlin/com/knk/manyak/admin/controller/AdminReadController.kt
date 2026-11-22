package com.knk.manyak.admin.controller

import com.knk.manyak.admin.dto.*
import com.knk.manyak.adminkit.query.AdminListRequest
import com.knk.manyak.adminkit.security.AdminRequester
import com.knk.manyak.admin.service.AdminReadService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 이 컨트롤러는 여러 도메인의 조회를 모아 두어 OpenAPI 태그를 메서드마다 도메인으로 붙인다(KNK-2124).
// 이름과 설명은 같은 태그를 쓰는 다른 관리자 컨트롤러와 같아야 한다.
private const val DASHBOARD = "Admin Dashboard"
private const val DASHBOARD_DESC = "관리자 대시보드 API"
private const val PAYMENTS = "Admin Payments"
private const val PAYMENTS_DESC = "관리자 결제 API"
private const val MEMBERS = "Admin Members"
private const val MEMBERS_DESC = "관리자 회원 API"
private const val STORIES = "Admin Stories"
private const val STORIES_DESC = "관리자 스토리 API"
private const val CHATS = "Admin Chats"
private const val CHATS_DESC = "관리자 채팅 API"
private const val CREDITS = "Admin Credits"
private const val CREDITS_DESC = "관리자 이프 API"
private const val AUDIT_LOGS = "Admin Audit Logs"
private const val AUDIT_LOGS_DESC = "관리자 감사 기록 API"

@RestController
@RequestMapping("/api/v1/admin")
class AdminReadController(private val service: AdminReadService, private val charts: com.knk.manyak.admin.service.AdminChartsService) {
    @Tag(name = DASHBOARD, description = DASHBOARD_DESC)
    @GetMapping("/dashboard/summary")
    fun summary() = service.summary()

    @Tag(name = PAYMENTS, description = PAYMENTS_DESC)
    @GetMapping("/payments/orders")
    fun payments(@Valid @ModelAttribute request: AdminPaymentsRequest) = service.payments(request)

    @Tag(name = MEMBERS, description = MEMBERS_DESC)
    @GetMapping("/members")
    fun members(@Valid @ModelAttribute query: AdminMembersRequest, request: HttpServletRequest) =
        service.members(AdminRequester.userId(request), query)

    @Tag(name = MEMBERS, description = MEMBERS_DESC)
    @GetMapping("/members/{memberId}")
    fun member(@PathVariable memberId: UUID, request: HttpServletRequest) = service.member(AdminRequester.userId(request), memberId)

    @Tag(name = DASHBOARD, description = DASHBOARD_DESC)
    @GetMapping("/dashboard/charts")
    fun charts(@Valid @ModelAttribute request: AdminChartsRequest) = charts.charts(request)

    @Tag(name = STORIES, description = STORIES_DESC)
    @GetMapping("/stories")
    fun stories(@Valid @ModelAttribute request: AdminStoriesRequest) = service.stories(request)

    @Tag(name = STORIES, description = STORIES_DESC)
    @GetMapping("/stories/{storyId}")
    fun story(@PathVariable storyId: UUID) = service.story(storyId)

    @Tag(name = STORIES, description = STORIES_DESC)
    @io.swagger.v3.oas.annotations.Operation(summary = "스토리 설정", description = "삭제된 스토리를 포함한 모든 스토리의 현재 저장값 원문과 이미지 전부를 검수 상태, 삭제 시각과 함께 돌려줍니다. 승인 전 제출본은 반영하지 않으며 접속 기록은 남기지 않습니다.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = com.knk.manyak.global.config.OpenApiConfig.SECURITY_SCHEME_NAME)
    @GetMapping("/stories/{storyId}/settings")
    fun storySettings(@PathVariable storyId: UUID) = service.storySettings(storyId)

    @Tag(name = CHATS, description = CHATS_DESC)
    @GetMapping("/chats")
    fun chats(@Valid @ModelAttribute request: AdminChatsRequest) = service.chats(request)

    @Tag(name = CHATS, description = CHATS_DESC)
    @GetMapping("/chats/{chatId}")
    fun chat(@PathVariable chatId: UUID) = service.chat(chatId)

    @Tag(name = PAYMENTS, description = PAYMENTS_DESC)
    @GetMapping("/payments/orders/{orderId}")
    fun payment(@PathVariable orderId: UUID) = service.payment(orderId)

    @Tag(name = CREDITS, description = CREDITS_DESC)
    @io.swagger.v3.oas.annotations.Operation(summary = "전체 이프 거래 내역", description = "팀 계정 제외를 적용하지 않아 대시보드의 지급·소진 합계와 다를 수 있습니다. 접속 기록은 남기지 않습니다.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = com.knk.manyak.global.config.OpenApiConfig.SECURITY_SCHEME_NAME)
    @GetMapping("/credits/transactions")
    fun creditTransactions(@org.springdoc.core.annotations.ParameterObject @Valid @ModelAttribute request: AdminCreditTransactionsRequest) = service.creditTransactions(request)

    @Tag(name = MEMBERS, description = MEMBERS_DESC)
    @GetMapping("/members/{memberId}/transactions")
    fun transactions(@PathVariable memberId: UUID, @Valid @ModelAttribute query: AdminListRequest, request: HttpServletRequest) =
        service.transactions(AdminRequester.userId(request), memberId, query)

    @Tag(name = MEMBERS, description = MEMBERS_DESC)
    @GetMapping("/members/{memberId}/attendance")
    fun attendance(@PathVariable memberId: UUID, @Valid @ModelAttribute query: AdminListRequest, request: HttpServletRequest) =
        service.attendance(AdminRequester.userId(request), memberId, query)

    @Tag(name = AUDIT_LOGS, description = AUDIT_LOGS_DESC)
    @GetMapping("/audit-logs")
    fun auditLogs(@Valid @ModelAttribute request: AdminAuditLogsRequest) = service.auditLogs(request)
}
