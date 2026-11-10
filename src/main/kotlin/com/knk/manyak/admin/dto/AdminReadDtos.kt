package com.knk.manyak.admin.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.adminkit.audit.AdminAuditAction
import com.knk.manyak.adminkit.audit.AdminAuditTargetType
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.credit.entity.CreditOrderStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

class AdminMembersRequest : AdminSearchRequest() {
    var status: UserStatus? = null
    var excludeDeleted: Boolean? = null
    @field:Min(0) @field:Max(4) var chatBucket: Int? = null
    var chatFrom: String? = null
    var chatTo: String? = null
    var creditDirection: String? = null
    var creditDate: String? = null
    var attendanceDate: String? = null
}
class AdminCreditTransactionsRequest : AdminListRequest() {
    @field:Pattern(regexp = "granted|spent") var direction: String? = null
    var date: String? = null
}

data class AdminCreditTransactionResponse(
    val amount: Long, val reason: String, val createdAt: Instant, val member: AdminMemberSummary,
)

class AdminPaymentsRequest : AdminSearchRequest() {
    var status: CreditOrderStatus? = null
    var memberId: UUID? = null
}
class AdminAuditLogsRequest : AdminSearchRequest() {
    var adminId: UUID? = null
    var action: AdminAuditAction? = null
    var targetType: AdminAuditTargetType? = null
    var category: com.knk.manyak.adminkit.audit.AdminAuditCategory? = null
    @field:Size(min = 1, max = 255) var targetId: String? = null
}

data class AdminMemberDetailResponse(
    // isAdmin: springdoc이 is 접두를 벗기지 않도록 와이어 필드명을 고정한다(AdminMemberSummary와 동일).
    val memberId: UUID, val nickname: String, val status: UserStatus, @get:JsonProperty("isAdmin") val isAdmin: Boolean, val createdAt: Instant,
    val lastActiveAt: Instant?, val creditsSpent: Long, val paidKrw: Long, val chatCount: Long, val periodChatCount: Long?,
    val creditBalance: Long, val storyCount: Long, val turnCount: Long, val deletedAt: Instant?,
    val periodTurnCount: Long?,
) {
    constructor(member: AdminMemberListResponse, creditBalance: Long, storyCount: Long, deletedAt: Instant?) : this(
        member.memberId, member.nickname, member.status, member.isAdmin, member.createdAt, member.lastActiveAt,
        member.creditsSpent, member.paidKrw, member.chatCount, member.periodChatCount, creditBalance, storyCount, member.turnCount, deletedAt, member.periodTurnCount)
}

data class AdminPaymentOrderResponse(
    val orderId: UUID, val member: AdminMemberSummary, val productId: String, val status: CreditOrderStatus,
    val priceKrw: Long, val creditAmount: Long, val completedAt: Instant, val refundedAt: Instant?, val reversalShortfall: Long?,
)
data class AdminAuditLogResponse(
    val auditLogId: UUID, val admin: AdminMemberSummary, val category: String, val action: String,
    val targetType: String, val targetId: String, val reason: String?, val before: JsonNode?, val after: JsonNode?,
    val requestId: String, val createdAt: Instant,
)
data class AdminDashboardSummaryResponse(
    val asOf: Instant, val todayStartsAt: Instant, val members: AdminMemberMetrics, val chats: AdminChatMetrics,
    val stories: AdminStoryMetrics, val credits: AdminCreditMetrics, val payments: AdminPaymentMetrics,
)
data class AdminMemberMetrics(val total: Long, val active: Long, val withdrawn: Long, val joinedToday: Long)
data class AdminChatMetrics(val startedTotal: Long, val startedToday: Long, val turnsTotal: Long, val turnsToday: Long)
data class AdminStoryMetrics(val total: Long, val createdToday: Long)
data class AdminCreditMetrics(val grantedTotal: Long, val grantedToday: Long, val spentTotal: Long, val spentToday: Long)
data class AdminPaymentMetrics(val completedTotal: Long, val completedToday: Long, val amountKrwTotal: Long, val amountKrwToday: Long)

