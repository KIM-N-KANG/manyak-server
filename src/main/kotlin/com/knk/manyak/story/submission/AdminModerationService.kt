package com.knk.manyak.story.submission

import com.knk.manyak.admin.entity.AdminAuditAction
import com.knk.manyak.admin.service.AdminAuditService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Service
class AdminModerationService(
    private val store: AdminModerationStore,
    private val audits: AdminAuditService,
    private val mapper: ObjectMapper,
    private val users: com.knk.manyak.auth.repository.UserRepository,
    private val reads: AdminSubmissionReadRepository,
    private val submissions: StorySubmissionRepository,
    private val submissionForms: StorySubmissionService,
    private val forms: SubmissionFormAssembler,
) {
    fun holds(query: AdminHoldListRequest) = store.holds(query)

    @Transactional(readOnly = true)
    fun submissions(query: AdminSubmissionListRequest) = reads.list(query)

    @Transactional(readOnly = true)
    fun submission(value: String): AdminSubmissionDetail {
        val id=runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString().equals(value,ignoreCase=true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"제출본을 찾을 수 없습니다.")
        val row=submissions.findByPublicId(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"제출본을 찾을 수 없습니다.")
        val summary=reads.summary(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"제출본을 찾을 수 없습니다.")
        val form=submissionForms.restoredForm(row)
        fun text(name: String) = form[name]?.takeUnless { it.isNull }?.asText()
        val original=mapper.readTree(row.inputForm)
        val issues=forms.remap(row.issues,original,form)
        val errors=forms.remapImageErrors(row.imageErrors,original,form)
        val fields=AdminSubmissionFields(requireNotNull(text("title")),text("oneLineIntro"),text("description"),text("protagonistName"),
            form.path("genres").toList().map { it.asText() },requireNotNull(text("visibility")),form.path("characters").toList().map { it.path("name").asText() })
        return AdminSubmissionDetail(summary,fields,issues,imageResults(row.status,form,issues,errors),row.retryCount,row.updatedAt)
    }

    private fun imageResults(status: SubmissionStatus, form: tools.jackson.databind.JsonNode, issues: List<ModerationIssue>, errors: List<SubmissionImageError>): List<AdminSubmissionImageResult> {
        val result=mutableListOf<AdminSubmissionImageResult>()
        fun add(path: String, label: String) {
            val error=errors.firstOrNull { it.path==path }
            val state=when {
                issues.any { it.path==path && it.type=="IMAGE" } -> AdminSubmissionImageStatus.REJECTED
                error!=null -> AdminSubmissionImageStatus.ERROR
                status==SubmissionStatus.APPROVED || status==SubmissionStatus.REJECTED -> AdminSubmissionImageStatus.APPROVED
                else -> AdminSubmissionImageStatus.PENDING
            }
            result+=AdminSubmissionImageResult(path,label,state,error?.errorCode?.takeIf { state==AdminSubmissionImageStatus.ERROR })
        }
        if(listOf("thumbnailUrl","thumbnailObjectKey").any { form[it]?.takeUnless { value -> value.isNull }?.asText()?.isNotBlank()==true }) add("thumbnailUrl","표지")
        form.path("characters").toList().forEachIndexed { c, character ->
            character.path("images").toList().forEachIndexed { i, image -> add("characters[$c].images[$i].imageUrl",image.path("imageName").asText()) }
        }
        return result
    }

    @Transactional
    fun release(adminUserId: Long, submissionId: UUID, reason: String): AdminHoldReleased {
        // 감사 FK의 처리자 잠금을 제출본보다 먼저 확보한다. 처리자 소유 제출본의 취소와 잠금 순서를 맞춘다.
        users.findByIdForUpdate(adminUserId) ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 없습니다.")
        val previous = store.lock(submissionId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "제출본을 찾을 수 없습니다.")
        val now = Instant.now()
        if (store.release(submissionId, now) != 1) throw ResponseStatusException(HttpStatus.CONFLICT, "보류 중인 제출본이 아닙니다.")
        audits.recordChange(adminUserId, AdminAuditAction.SUBMISSION_HOLD_RELEASED, submissionId.toString(), reason,
            mapper.valueToTree(mapOf("holdReason" to previous.holdReason, "attempt" to previous.attempt)))
        return AdminHoldReleased(submissionId, SubmissionStatus.PENDING, previous.attempt, now)
    }
}

@Repository
class AdminModerationStore(private val jdbc: NamedParameterJdbcTemplate) {
    fun holds(query: AdminHoldListRequest): AdminHoldPage {
        val orderBy = query.orderBy()
        val from = """FROM story_submissions s JOIN users u ON u.id = s.user_id LEFT JOIN stories t ON t.id = s.story_id
            WHERE s.status = 'PENDING' AND s.held_at IS NOT NULL"""
        val total = jdbc.queryForObject("SELECT COUNT(*) $from", emptyMap<String, Any>(), Long::class.java)!!
        val items = if (query.offset >= total) emptyList() else jdbc.query("""SELECT s.public_id, s.kind, t.public_id AS story_public_id,
            u.public_id AS owner_public_id, s.hold_reason, s.attempt, s.held_at, s.created_at
            $from ORDER BY $orderBy LIMIT :size OFFSET :offset""", mapOf("size" to query.size, "offset" to query.offset)) { rs, _ ->
            AdminHoldSummary(rs.getObject("public_id", UUID::class.java), SubmissionKind.valueOf(rs.getString("kind")),
                rs.getObject("story_public_id", UUID::class.java), rs.getObject("owner_public_id", UUID::class.java),
                rs.getString("hold_reason"), rs.getInt("attempt"), rs.getTimestamp("held_at").toInstant(), rs.getTimestamp("created_at").toInstant())
        }
        return AdminHoldPage(items, query.page, query.size, total, (total + query.size - 1) / query.size)
    }

    // 원문을 로드하지 않고 해제·취소를 직렬화해 감사의 변경 전 값을 확정한다.
    fun lock(id: UUID): HeldState? = jdbc.query("SELECT hold_reason, attempt FROM story_submissions WHERE public_id = :id FOR UPDATE",
        mapOf("id" to id)) { rs, _ -> HeldState(rs.getString("hold_reason"), rs.getInt("attempt")) }.singleOrNull()

    fun release(id: UUID, now: Instant): Int = jdbc.update("""UPDATE story_submissions
        SET held_at = NULL, hold_reason = NULL, retry_count = 0, next_attempt_at = NULL, dispatched_at = NULL, updated_at = :now
        WHERE public_id = :id AND status = 'PENDING' AND held_at IS NOT NULL""", mapOf("id" to id, "now" to now.atOffset(ZoneOffset.UTC)))
}

data class HeldState(val holdReason: String?, val attempt: Int)
