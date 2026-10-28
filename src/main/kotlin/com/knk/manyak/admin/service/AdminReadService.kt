package com.knk.manyak.admin.service

import com.knk.manyak.admin.config.AdminDashboardProperties
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.entity.AdminAuditAction
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
    private val content: com.knk.manyak.admin.repository.AdminContentRepository,
    private val official: com.knk.manyak.story.service.OfficialStoryAccount,
    private val dashboard: AdminDashboardRepository,
    private val properties: AdminDashboardProperties,
    private val users: UserRepository,
    private val wallet: CreditWalletService,
    private val audits: AdminAuditService,
    private val storyRepository: com.knk.manyak.story.repository.StoryRepository,
    private val edit: com.knk.manyak.story.service.StoryEditService,
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
        return reads.payments(request, page)
    }

    fun members(adminUserId: Long, request: AdminMembersRequest): AdminPageResponse<AdminMemberListResponse> = SocialAuthPrivacyFilter.safely {
        val query = request.normalizedQuery()
        if (request.creditDirection != null && request.creditDirection !in setOf("granted", "spent")) badAdminQuery("원장 방향이 올바르지 않습니다.")
        if (request.creditDate != null && request.creditDirection == null) badAdminQuery("creditDate는 creditDirection과 함께 지정해야 합니다.")
        AdminDateRange.of(request.creditDate, request.creditDate)
        AdminDateRange.of(request.attendanceDate, request.attendanceDate)
        val page = request.pagination(mapOf("createdAt" to "u.created_at", "lastActiveAt" to "last_active_at",
            "creditsSpent" to "credits_spent", "paidKrw" to "paid_krw", "chatCount" to "chat_count"), "createdAt")
        val result = reads.members(request, page)
        if (query != null) audits.recordAccess(adminUserId, AdminAuditAction.MEMBER_SEARCHED, query)
        result
    }

    fun member(adminUserId: Long, memberId: UUID): AdminMemberDetailResponse = SocialAuthPrivacyFilter.safely {
        val user = requireMember(memberId)
        val summary = reads.members(AdminMembersRequest(), AdminPagination(1, 1, "u.created_at desc"), memberId).items.single()
        val result = AdminMemberDetailResponse(summary, wallet.balanceOf(user.id), reads.contentCount("stories", user.id), reads.memberTurnCount(user.id), user.deletedAt)
        audits.recordAccess(adminUserId, AdminAuditAction.MEMBER_VIEWED, user.publicId.toString())
        result
    }

    fun creditTransactions(request: AdminCreditTransactionsRequest): AdminPageResponse<AdminCreditTransactionResponse> {
        if (request.direction != null && request.direction !in setOf("granted", "spent")) badAdminQuery("원장 방향이 올바르지 않습니다.")
        AdminDateRange.of(request.date, request.date)
        val page = request.pagination(mapOf("createdAt" to "t.created_at"), "createdAt")
        return reads.creditTransactions(request, page)
    }

    fun transactions(adminUserId: Long, memberId: UUID, request: AdminListRequest): AdminPageResponse<AdminTransactionResponse> = SocialAuthPrivacyFilter.safely {
        val page = request.pagination(mapOf("createdAt" to "created_at"), "createdAt")
        val user = requireMember(memberId)
        val result = reads.transactions(user.id, page)
        audits.recordAccess(adminUserId, AdminAuditAction.MEMBER_VIEWED, memberId.toString())
        result
    }
    fun attendance(adminUserId: Long, memberId: UUID, request: AdminListRequest): AdminPageResponse<AdminAttendanceResponse> = SocialAuthPrivacyFilter.safely {
        val page = request.pagination(mapOf("date" to "attendance_date"), "date")
        val user = requireMember(memberId)
        val result = reads.attendance(user.id, page)
        audits.recordAccess(adminUserId, AdminAuditAction.MEMBER_VIEWED, memberId.toString())
        result
    }
    fun payment(orderId: UUID): AdminPaymentDetailResponse {
        val order = reads.payment(orderId) ?: notFound()
        val first = reads.firstPaidAt(order.member.memberId)
        val usage = reads.paymentUsage(requireMember(order.member.memberId).id, first)
        return AdminPaymentDetailResponse(order, first, usage.first, usage.second)
    }

    fun stories(request: AdminStoriesRequest): AdminPageResponse<AdminStoryListResponse> {
        if (request.scope != null && request.scope !in setOf("original", "user")) badAdminQuery("스토리 범위가 올바르지 않습니다.")
        if (request.visibility != null && request.visibility !in setOf("PUBLIC", "PRIVATE")) badAdminQuery("공개 범위가 올바르지 않습니다.")
        if (request.moderationStatus != null && request.moderationStatus !in setOf("HELD", "APPROVED")) badAdminQuery("검수 상태가 올바르지 않습니다.")
        if (request.creationMode != null && request.creationMode !in setOf("SIMPLE", "GENERAL")) badAdminQuery("제작 방식이 올바르지 않습니다.")
        if (request.genre != null && (request.genre!!.isBlank() || request.genre!!.length > 30 || request.genre!!.contains(","))) badAdminQuery("장르가 올바르지 않습니다.")
        request.validateAi(setOf("storyline_generation", "story_completion"))
        AdminDateRange.of(request.creationFrom, request.creationTo)
        validateDeleted(request.deleted)
        return content.stories(request, request.pagination(mapOf("createdAt" to "s.created_at", "chatCount" to "chat_count", "totalTurns" to "total_turns"), "createdAt"), official.officialUserId())
    }
    fun story(storyId: UUID): AdminStoryDetailResponse {
        val story = content.stories(AdminStoriesRequest().apply { deleted = "include" }, AdminPagination(1, 1, "s.created_at desc"), official.officialUserId(), storyId).items.firstOrNull() ?: notFound()
        val recent = content.chats(AdminChatsRequest().apply { this.storyId = storyId }, AdminPagination(1, 5, "c.created_at desc")).items
        return AdminStoryDetailResponse(story, content.retention(storyId), recent)
    }
    /** 4-2-7. 삭제 여부와 소유자 검사 없이 현재 저장값 수정 폼 조립을 쓴다. 접속 기록 없음. */
    fun storySettings(storyId: UUID): AdminStorySettingsResponse {
        val story = storyRepository.findByPublicId(storyId) ?: notFound()
        return AdminStorySettingsResponse(edit.getCurrentForm(story), story.deletedAt)
    }
    fun chats(request: AdminChatsRequest): AdminPageResponse<AdminChatResponse> {
        if (request.startedAs != null && request.startedAs !in setOf("GUEST", "MEMBER")) badAdminQuery("채팅 시작 구분이 올바르지 않습니다.")
        request.validateAi(setOf("chat_response", "choice_generation", "chat_image"))
        AdminDateRange.of(request.turnDate, request.turnDate)
        validateDeleted(request.deleted)
        return content.chats(request, request.pagination(mapOf("startedAt" to "c.created_at", "lastActiveAt" to "c.updated_at",
            "turnCount" to "c.current_turn", "creditsSpent" to "credits_spent"), "startedAt"))
    }
    fun chat(chatId: UUID): AdminChatResponse = content.chats(AdminChatsRequest().apply { deleted = "include" }, AdminPagination(1, 1, "c.created_at desc"), chatId).items.firstOrNull() ?: notFound()

    fun auditLogs(request: AdminAuditLogsRequest): AdminPageResponse<AdminAuditLogResponse> {
        if (request.targetId != null && request.targetType == null) badAdminQuery("targetId는 targetType과 함께 지정해야 합니다.")
        return reads.auditLogs(request, request.pagination(mapOf("createdAt" to "l.created_at", "category" to "l.category", "action" to "l.action",
            "targetType" to "l.target_type", "admin" to "u.nickname"), "createdAt"))
    }
    private fun validateDeleted(value: String?) { if (value != null && value !in setOf("include", "only")) badAdminQuery("삭제 필터가 올바르지 않습니다.") }
    private fun requireMember(id: UUID) = users.findByPublicId(id) ?: notFound()
    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.")
}
