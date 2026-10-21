package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.repository.AdminAccountRepository
import com.knk.manyak.admin.repository.AdminDashboardRepository
import com.knk.manyak.admin.repository.AdminReadRepository
import com.knk.manyak.auth.consent.SocialAuthPrivacyFilter
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditOrderStatus
import com.knk.manyak.credit.service.CreditWalletService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

@Service
class AdminReadService(
    private val reads: AdminReadRepository,
    private val dashboard: AdminDashboardRepository,
    private val properties: AdminDashboardProperties,
    private val users: UserRepository,
    private val accounts: AdminAccountRepository,
    private val wallet: CreditWalletService,
    private val audits: AdminAuditService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun summary(): AdminDashboardSummaryResponse {
        val now = clock.instant()
        val today = now.atZone(AdminDateRange.KST).toLocalDate().atStartOfDay(AdminDateRange.KST).toInstant()
        return dashboard.summary(now, today, properties.excludedUserIds)
    }

    fun payments(request: AdminPaymentsRequest): AdminPageResponse<AdminPaymentOrderResponse> {
        if (request.status == CreditOrderStatus.PENDING) badAdminQuery("결제 상태가 올바르지 않습니다.")
        val page = request.pagination(mapOf("priceKrw" to "o.price_krw", "creditAmount" to "o.credit_amount",
            "completedAt" to "o.completed_at", "refundedAt" to "o.refunded_at"), "completedAt")
        return reads.payments(request, AdminDateRange.of(request.from, request.to), page)
    }

    fun members(adminUserId: Long, request: AdminMembersRequest): AdminPageResponse<AdminMemberSummary> = SocialAuthPrivacyFilter.safely {
        val query = request.query
        if (query != null && query.isBlank()) badAdminQuery("검색어는 공백일 수 없습니다.")
        val page = request.pagination(mapOf("createdAt" to "u.created_at"), "createdAt")
        val result = reads.members(query, request.status, page)
        audits.recordAccess(adminUserId, AdminAuditAction.MEMBER_SEARCHED, query ?: "ALL")
        result
    }

    fun member(adminUserId: Long, memberId: UUID): AdminMemberDetailResponse = SocialAuthPrivacyFilter.safely {
        val user = users.findByPublicId(memberId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.")
        val result = AdminMemberDetailResponse(user.publicId, user.nickname, user.status, accounts.existsById(user.id), user.createdAt,
            wallet.balanceOf(user.id), reads.contentCount("stories", user.id), reads.contentCount("story_chats", user.id), user.deletedAt)
        audits.recordAccess(adminUserId, AdminAuditAction.MEMBER_VIEWED, user.publicId.toString())
        result
    }

    fun auditLogs(request: AdminAuditLogsRequest): AdminPageResponse<AdminAuditLogResponse> {
        if (request.targetId != null && request.targetType == null) badAdminQuery("targetId는 targetType과 함께 지정해야 합니다.")
        return reads.auditLogs(request, request.pagination(mapOf("createdAt" to "l.created_at"), "createdAt"))
    }
}
