package com.knk.manyak.story.submission

import com.knk.manyak.admin.dto.AdminOperationReason
import com.knk.manyak.admin.security.AdminRequester
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/moderation")
@SecurityRequirement(name = "bearerAuth")
class AdminModerationController(private val service: AdminModerationService) {
    @GetMapping("/holds")
    fun holds(@Valid @ModelAttribute query: AdminHoldListRequest) = service.holds(query)

    @GetMapping("/submissions")
    fun submissions(@org.springdoc.core.annotations.ParameterObject @Valid @ModelAttribute query: AdminSubmissionListRequest) = service.submissions(query)

    @GetMapping("/submissions/{submissionId}")
    fun submission(@PathVariable submissionId: String) = service.submission(submissionId)

    @PostMapping("/holds/{submissionId}/release")
    fun release(@PathVariable submissionId: UUID, @Valid @RequestBody body: AdminOperationReason, request: HttpServletRequest) =
        service.release(AdminRequester.userId(request), submissionId, body.reason)
}

/** S2가 합쳐지면 페이지·정렬 검증을 공용화한다. */
class AdminHoldListRequest {
    @field:Min(1) var page: Int = 1
    @field:Min(1) @field:Max(100) var size: Int = 20
    var sort: String = "heldAt"
    @field:Pattern(regexp = "asc|desc") var order: String = "asc"
    var limit: String? = null
    var cursor: String? = null
    val offset: Long get() = (page.toLong() - 1) * size
    fun orderBy(): String {
        val column = when (sort) {
            "heldAt" -> "s.held_at"
            "createdAt" -> "s.created_at"
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "정렬 열이 올바르지 않습니다.")
        }
        if (page < 1 || size !in 1..100 || order !in setOf("asc", "desc") || limit != null || cursor != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지와 정렬 값이 올바르지 않습니다.")
        }
        return "$column $order, s.public_id ASC"
    }
}

data class AdminHoldPage(val items: List<AdminHoldSummary>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Long)
data class AdminHoldSummary(val submissionId: UUID, val kind: SubmissionKind, val storyId: UUID?, val ownerId: UUID,
                            val holdReason: String, val attempt: Int, val heldAt: Instant, val createdAt: Instant)
data class AdminHoldReleased(val submissionId: UUID, val status: SubmissionStatus, val attempt: Int, val releasedAt: Instant)
