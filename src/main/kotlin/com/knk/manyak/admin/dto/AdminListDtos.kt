package com.knk.manyak.admin.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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
data class AdminPagination(val page: Int, val size: Int, val orderBy: String) {
    val offset: Long get() = (page.toLong() - 1) * size
    fun <T> response(items: List<T>, total: Long) = AdminPageResponse(items, page, size, total, (total + size - 1) / size)
}
data class AdminPageResponse<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Long)
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
