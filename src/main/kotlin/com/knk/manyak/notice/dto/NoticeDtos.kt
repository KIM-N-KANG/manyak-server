package com.knk.manyak.notice.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.knk.manyak.notice.entity.NoticeStatus
import com.knk.manyak.notice.entity.NoticeTag
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.*
import java.time.Instant
import java.util.UUID

data class CreateNoticeRequest(
    @field:NotBlank @field:Size(max = 100) val title: String,
    @field:NotBlank @field:Size(max = 5000) val body: String,
    val tag: NoticeTag,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
data class UpdateNoticeRequest(
    val tag: NoticeTag? = null,
    @field:Size(min = 1, max = 100) @field:Pattern(regexp = "(?sU).*\\S.*") val title: String? = null,
    @field:Size(min = 1, max = 5000) @field:Pattern(regexp = "(?sU).*\\S.*") val body: String? = null,
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
@JsonIgnoreProperties(value = ["notify", "notificationKind"])
data class PublishNoticeRequest(
    @field:NotBlank @field:Size(max = 500) val reason: String,
)
data class NoticeReasonRequest(@field:NotBlank @field:Size(max = 500) val reason: String)
class AdminNoticesRequest {
    @field:Size(min = 1, max = 50) var q: String? = null
    var status: NoticeStatus? = null
    @field:Min(1) var page: Int = 1
    @field:Min(1) @field:Max(100) var size: Int = 20
    var sort: String = "createdAt"
    @field:Pattern(regexp = "asc|desc") var order: String = "desc"
    var cursor: String? = null
    var limit: String? = null
}
data class NoticeAuthorResponse(val memberId: UUID, val nickname: String)
data class AdminNoticeResponse(
    val noticeId: UUID, val title: String, val tag: NoticeTag,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val body: String?,
    val status: NoticeStatus, val publishedAt: Instant?, val notifiedAt: Instant?, val createdBy: NoticeAuthorResponse,
    val createdAt: Instant, val updatedAt: Instant,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val notified: Boolean? = null,
)
data class AdminNoticePageResponse(val items: List<AdminNoticeResponse>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Long)
data class PublicNoticeResponse(val noticeId: UUID, val title: String, val tag: NoticeTag, val body: String, val publishedAt: Instant, val updatedAt: Instant)
data class PublicNoticeItemResponse(val noticeId: UUID, val title: String, val tag: NoticeTag, val publishedAt: Instant)
data class PublicNoticePageResponse(val items: List<PublicNoticeItemResponse>, val nextCursor: String?)
