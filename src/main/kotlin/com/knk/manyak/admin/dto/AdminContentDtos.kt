package com.knk.manyak.admin.dto

import com.knk.manyak.auth.entity.UserStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

open class AdminSearchRequest : AdminListRequest() {
    @field:Size(min = 1, max = 50) var q: String? = null
    var from: String? = null
    var to: String? = null
    fun normalizedQuery(): String? = q?.let {
        val normalized = it.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        if (normalized.isEmpty() || it.length > 50) badAdminQuery("검색어는 1~50자여야 합니다.")
        normalized
    }
}
open class AdminAiRequest : AdminSearchRequest() {
    var aiFrom: String? = null
    var aiTo: String? = null
    var aiFeature: String? = null
    var aiStatus: String? = null
    var hasLatency: Boolean? = null
    fun validateAi(features: Set<String>) {
        if (aiFeature != null && aiFeature !in features) badAdminQuery("AI 기능이 올바르지 않습니다.")
        if (aiStatus != null && aiStatus !in setOf("SUCCEEDED", "FAILED", "STARTED", "FINISHED")) badAdminQuery("AI 상태가 올바르지 않습니다.")
        AdminDateRange.of(aiFrom, aiTo)
    }
}
class AdminStoriesRequest : AdminAiRequest() {
    var scope: String? = null
    var genre: String? = null
    var visibility: String? = null
    var moderationStatus: String? = null
    var memberId: UUID? = null
    @field:Min(0) @field:Max(5) var supportingCharacterCount: Int? = null
    var chatFrom: String? = null
    var chatTo: String? = null
    var creationFrom: String? = null
    var creationTo: String? = null
}
class AdminChatsRequest : AdminAiRequest() {
    var storyId: UUID? = null
    var memberId: UUID? = null
    var startedAs: String? = null
    @field:Min(0) @field:Max(10) var turnBucket: Int? = null
    var hasFirstMessage: Boolean? = null
    var turnDate: String? = null
}
class AdminChartsRequest {
    var days: Int = 30
}

data class AdminMemberListResponse(
    val memberId: UUID, val nickname: String, val status: UserStatus, val isAdmin: Boolean, val createdAt: Instant,
    val lastActiveAt: Instant?, val creditsSpent: Long, val paidKrw: Long, val chatCount: Long, val periodChatCount: Long?,
)
data class AdminStorySummary(val storyId: UUID, val title: String, val genre: String)
data class AdminStoryListResponse(
    val storyId: UUID, val title: String, val genre: String, val description: String?, val scope: String,
    val visibility: String, val moderationStatus: String, val tags: List<String>, val supportingCharacterCount: Long?,
    val owner: AdminMemberSummary?, val chatCount: Long, val totalTurns: Long, val createdAt: Instant,
)
data class AdminStoryDetailResponse(
    val storyId: UUID, val title: String, val genre: String, val description: String?, val scope: String,
    val visibility: String, val moderationStatus: String, val tags: List<String>, val supportingCharacterCount: Long?,
    val owner: AdminMemberSummary?, val chatCount: Long, val totalTurns: Long, val createdAt: Instant,
    val retention: List<AdminRetention>, val recentChats: List<AdminChatResponse>,
) {
    constructor(story: AdminStoryListResponse, retention: List<AdminRetention>, recentChats: List<AdminChatResponse>) : this(
        story.storyId, story.title, story.genre, story.description, story.scope, story.visibility, story.moderationStatus,
        story.tags, story.supportingCharacterCount, story.owner, story.chatCount, story.totalTurns, story.createdAt, retention, recentChats)
}

data class AdminRetention(val turn: Int, val rate: Double)
data class AdminChatResponse(
    val chatId: UUID, val story: AdminStorySummary, val member: AdminMemberSummary?, val startedAs: String,
    val turnCount: Int, val startedAt: Instant, val lastActiveAt: Instant, val creditsSpent: Long, val firstMessageSeconds: Long?,
)
data class AdminTransactionResponse(val amount: Long, val reason: String, val balanceAfter: Long, val createdAt: Instant)
data class AdminAttendanceResponse(val date: LocalDate, val creditAmount: Long)
data class AdminPaymentDetailResponse(
    val orderId: UUID, val member: AdminMemberSummary, val productId: String, val status: com.knk.manyak.credit.entity.CreditOrderStatus,
    val priceKrw: Long, val creditAmount: Long, val completedAt: Instant, val refundedAt: Instant?, val reversalShortfall: Long?,
    val firstPaidAt: Instant, val before: AdminPaymentUsage, val after: AdminPaymentUsage,
) {
    constructor(order: AdminPaymentOrderResponse, firstPaidAt: Instant, before: AdminPaymentUsage, after: AdminPaymentUsage) : this(
        order.orderId, order.member, order.productId, order.status, order.priceKrw, order.creditAmount, order.completedAt,
        order.refundedAt, order.reversalShortfall, firstPaidAt, before, after)
}

data class AdminPaymentUsage(val turns: Long, val stories: Long, val creditsSpent: Long)
data class AdminChartsResponse(
    val asOf: Instant, val from: LocalDate, val to: LocalDate, val daily: List<AdminDailyMetrics>,
    val distributions: List<AdminDistribution>,
)
data class AdminDailyMetrics(
    val date: LocalDate, val joined: Long = 0, val chats: Long = 0, val turns: Long = 0, val stories: Long = 0,
    val granted: Long = 0, val spent: Long = 0, val payments: Long = 0, val revenue: Long = 0, val attendance: Long = 0,
)
data class AdminDistribution(val chartId: String, val title: String, val description: String, val unit: String, val points: List<AdminChartPoint>)
data class AdminChartPoint(val label: String, val value: Double, val link: AdminChartLink)
data class AdminChartLink(val list: String, val params: Map<String, String>)
