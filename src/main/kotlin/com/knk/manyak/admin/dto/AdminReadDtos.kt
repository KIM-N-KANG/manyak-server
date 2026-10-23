package com.knk.manyak.admin.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.entity.AdminAuditTargetType
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.credit.entity.CreditOrderStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

open class AdminListRequest {
    @field:Min(1) var page: Int = 1
    @field:Min(1) @field:Max(100) var size: Int = 20
    var cursor: String? = null
    var limit: String? = null
    var sort: String? = null
    @field:Pattern(regexp = "asc|desc") var order: String = "desc"

    fun pagination(columns: Map<String, String>, defaultSort: String): AdminPagination {
        if (cursor != null || limit != null) badAdminQuery("page와 size로 페이지를 지정하세요.")
        val column = columns[sort ?: defaultSort] ?: badAdminQuery("정렬 열이 올바르지 않습니다.")
        if (page < 1 || size !in 1..100 || order !in setOf("asc", "desc")) badAdminQuery("페이지와 정렬 값이 올바르지 않습니다.")
        return AdminPagination(page, size, "$column $order NULLS LAST")
    }
}
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
    var category: com.knk.manyak.admin.entity.AdminAuditCategory? = null
    @field:Size(min = 1, max = 255) var targetId: String? = null
}

data class AdminPagination(val page: Int, val size: Int, val orderBy: String) {
    val offset: Long get() = (page.toLong() - 1) * size
    fun <T> response(items: List<T>, total: Long) = AdminPageResponse(items, page, size, total, (total + size - 1) / size)
}
data class AdminPageResponse<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Long)
data class AdminMemberDetailResponse(
    // isAdmin: springdoc이 is 접두를 벗기지 않도록 와이어 필드명을 고정한다(AdminMemberSummary와 동일).
    val memberId: UUID, val nickname: String, val status: UserStatus, @get:JsonProperty("isAdmin") val isAdmin: Boolean, val createdAt: Instant,
    val lastActiveAt: Instant?, val creditsSpent: Long, val paidKrw: Long, val chatCount: Long, val periodChatCount: Long?,
    val creditBalance: Long, val storyCount: Long, val deletedAt: Instant?,
) {
    constructor(member: AdminMemberListResponse, creditBalance: Long, storyCount: Long, deletedAt: Instant?) : this(
        member.memberId, member.nickname, member.status, member.isAdmin, member.createdAt, member.lastActiveAt,
        member.creditsSpent, member.paidKrw, member.chatCount, member.periodChatCount, creditBalance, storyCount, deletedAt)
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
data class AdminMemberMetrics(val total: Long, val joinedToday: Long)
data class AdminChatMetrics(val startedTotal: Long, val startedToday: Long, val turnsTotal: Long, val turnsToday: Long)
data class AdminStoryMetrics(val total: Long, val createdToday: Long)
data class AdminCreditMetrics(val grantedTotal: Long, val grantedToday: Long, val spentTotal: Long, val spentToday: Long)
data class AdminPaymentMetrics(val completedTotal: Long, val completedToday: Long, val amountKrwTotal: Long, val amountKrwToday: Long)

/** 완료 시각의 KST 달력 날짜 범위. to 날짜의 다음 자정은 포함하지 않는다. */
data class AdminDateRange(val from: Instant?, val until: Instant?) {
    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        fun of(from: String?, to: String?): AdminDateRange {
            fun parse(value: String?) = value?.let {
                try { if (!it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) badAdminQuery("날짜가 올바르지 않습니다."); LocalDate.parse(it) } catch (_: java.time.DateTimeException) { badAdminQuery("날짜가 올바르지 않습니다.") }
            }
            val start = parse(from)
            val end = parse(to)
            if (start != null && end != null && start > end) badAdminQuery("시작일은 종료일 이후일 수 없습니다.")
            return AdminDateRange(start?.atStartOfDay(KST)?.toInstant(), end?.plusDays(1)?.atStartOfDay(KST)?.toInstant())
        }
    }
}
fun badAdminQuery(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
