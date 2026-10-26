package com.knk.manyak.admin.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.auth.entity.UserStatus
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import com.knk.manyak.image.service.ImageModerationStatus
import com.knk.manyak.story.dto.StoryEditFormResponse
import com.knk.manyak.story.entity.StoryVisibility
import io.swagger.v3.oas.annotations.media.Schema
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
    var deleted: String? = null
}
class AdminChatsRequest : AdminAiRequest() {
    var storyId: UUID? = null
    var memberId: UUID? = null
    var startedAs: String? = null
    @field:Min(0) @field:Max(12) var turnBucket: Int? = null
    var hasFirstMessage: Boolean? = null
    var turnDate: String? = null
    var deleted: String? = null
}
class AdminChartsRequest {
    @field:io.swagger.v3.oas.annotations.media.Schema(type = "integer", allowableValues = ["7", "30", "90"])
    var days: String? = null
    var from: String? = null
    var to: String? = null

    internal fun resolve(clock: java.time.Clock): AdminDashboardRange {
        val today = clock.instant().atZone(AdminDateRange.KST).toLocalDate()
        if ((from == null) != (to == null) || (days != null && from != null)) badAdminQuery("from과 to는 함께 지정하고 days와 함께 사용할 수 없습니다.")
        if (from != null) {
            AdminDateRange.of(from, to)
            val start = java.time.LocalDate.parse(from)
            val end = java.time.LocalDate.parse(to)
            if (end > today || java.time.temporal.ChronoUnit.DAYS.between(start, end) !in 0..365) badAdminQuery("기간은 오늘까지 1~366일이어야 합니다.")
            return AdminDashboardRange(start, end)
        }
        val length = if (days == null) 30 else days!!.toIntOrNull() ?: badAdminQuery("days는 7, 30, 90 중 하나여야 합니다.")
        if (length !in setOf(7, 30, 90)) badAdminQuery("days는 7, 30, 90 중 하나여야 합니다.")
        return AdminDashboardRange(today.minusDays(length.toLong() - 1), today)
    }
}

data class AdminMemberListResponse(
    // isAdmin: springdoc이 is 접두를 벗기지 않도록 와이어 필드명을 고정한다(AdminMemberSummary와 동일).
    val memberId: UUID, val nickname: String, val status: UserStatus, @get:JsonProperty("isAdmin") val isAdmin: Boolean, val createdAt: Instant,
    val lastActiveAt: Instant?, val creditsSpent: Long, val paidKrw: Long, val chatCount: Long, val periodChatCount: Long?,
)
data class AdminStorySummary(val storyId: UUID, val title: String, val genre: String)
data class AdminStoryListResponse(
    val storyId: UUID, val title: String, val genre: String, val description: String?, val scope: String,
    val visibility: String, val moderationStatus: String, val tags: List<String>, val supportingCharacterCount: Long?,
    val owner: AdminMemberSummary?, val chatCount: Long, val totalTurns: Long, val createdAt: Instant, val deletedAt: Instant?,
)
data class AdminStoryDetailResponse(
    val storyId: UUID, val title: String, val genre: String, val description: String?, val scope: String,
    val visibility: String, val moderationStatus: String, val tags: List<String>, val supportingCharacterCount: Long?,
    val owner: AdminMemberSummary?, val chatCount: Long, val totalTurns: Long, val createdAt: Instant, val deletedAt: Instant?,
    val retention: List<AdminRetention>, val recentChats: List<AdminChatResponse>,
) {
    constructor(story: AdminStoryListResponse, retention: List<AdminRetention>, recentChats: List<AdminChatResponse>) : this(
        story.storyId, story.title, story.genre, story.description, story.scope, story.visibility, story.moderationStatus,
        story.tags, story.supportingCharacterCount, story.owner, story.chatCount, story.totalTurns, story.createdAt, story.deletedAt, retention, recentChats)
}

data class AdminRetention(val turn: Int, val rate: Double)
data class AdminChatResponse(
    val chatId: UUID, val story: AdminStorySummary, val member: AdminMemberSummary?, val startedAs: String,
    val turnCount: Int, val startedAt: Instant, val lastActiveAt: Instant, val creditsSpent: Long, val firstMessageSeconds: Long?, val deletedAt: Instant?,
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
data class AdminDistribution(val chartId: String, val title: String, val description: String, val unit: String, val points: List<AdminChartPoint>) {
    val category: String = when (chartId) {
        "started-as", "member-chats" -> "users"
        "genre-depth", "turns", "first-message" -> "engagement"
        "characters" -> "content"
        "ai-latency", "ai-success" -> "ai-quality"
        else -> error("Unknown distribution: $chartId")
    }
}
data class AdminChartPoint(
    val label: String, val value: Double, val link: AdminChartLink,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val chats: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val totalTurns: Long? = null,
)
data class AdminChartLink(val list: String, val params: Map<String, String>)

internal data class AdminDashboardRange(val from: java.time.LocalDate, val to: java.time.LocalDate)

/**
 * ADMIN 4-2-7 스토리 설정. STORY 수정 폼의 현재 저장값에서 submission과 이미지 objectKey를 뺀 구조다.
 * 공용 응답 DTO와 달리 모든 필드가 항상 실린다는 것을 OpenAPI에 required로 내려고 관리자 전용 타입을 둔다
 * (`AdminStorySettings` 접두 스키마는 OpenApiConfig가 전 필드를 required로 표시).
 */
@Schema(description = "스토리 설정(현재 저장값 원문, 이미지 전부와 검수 상태)")
data class AdminStorySettingsResponse(
    val title: String,
    val oneLineIntro: String?,
    val description: String?,
    val protagonistName: String?,
    val genres: List<String>,
    val storySettings: AdminStorySettingsText,
    val startSettings: List<AdminStorySettingsStart>,
    val mainEvents: List<AdminStorySettingsMainEvent>,
    val visibility: StoryVisibility,
    val thumbnailUrl: String?,
    val thumbnailModerationStatus: ImageModerationStatus,
    val characters: List<AdminStorySettingsCharacter>,
    val deletedAt: Instant?,
) {
    constructor(form: StoryEditFormResponse, deletedAt: Instant?) : this(
        form.title, form.oneLineIntro, form.description, form.protagonistName, form.genres,
        form.storySettings.let { AdminStorySettingsText(it.worldSetting, it.characterSetting, it.userRoleSetting, it.ruleSetting) },
        form.startSettings.map { start ->
            AdminStorySettingsStart(start.id, start.name, start.prologue, start.startSituation, start.suggestedInputs, start.endings.map {
                AdminStorySettingsEnding(it.name, AdminStorySettingsRequirement(it.requirement.minTurns, it.requirement.achievementCondition), it.epilogue)
            })
        },
        form.mainEvents.map { AdminStorySettingsMainEvent(it.name, it.description, it.keySentence, it.sortOrder) },
        form.visibility, form.thumbnailUrl, form.thumbnailModerationStatus,
        // 현재 저장값 조립이라 인물과 이미지 공개 식별자는 항상 있다.
        form.characters.map { character ->
            AdminStorySettingsCharacter(requireNotNull(character.id), character.name, character.description, character.images.map {
                AdminStorySettingsImage(requireNotNull(it.id), it.imageName, it.imageUrl, it.moderationStatus)
            })
        },
        deletedAt,
    )
}

data class AdminStorySettingsText(val worldSetting: String?, val characterSetting: String?, val userRoleSetting: String?, val ruleSetting: String?)
data class AdminStorySettingsStart(
    val id: String, val name: String, val prologue: String?, val startSituation: String?,
    val suggestedInputs: List<String>, val endings: List<AdminStorySettingsEnding>,
)
data class AdminStorySettingsEnding(val name: String, val requirement: AdminStorySettingsRequirement, val epilogue: String)
data class AdminStorySettingsRequirement(val minTurns: Int, val achievementCondition: String)
data class AdminStorySettingsMainEvent(val name: String, val description: String, val keySentence: String, val sortOrder: Int)
data class AdminStorySettingsCharacter(val id: String, val name: String, val description: String?, val images: List<AdminStorySettingsImage>)
data class AdminStorySettingsImage(val id: String, val imageName: String, val imageUrl: String, val moderationStatus: ImageModerationStatus)
