package com.knk.manyak.story.submission

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.knk.manyak.admin.dto.*
import com.knk.manyak.admin.repository.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class AdminSubmissionListRequest : AdminSearchRequest() {
    @field:io.swagger.v3.oas.annotations.media.Schema(allowableValues = ["PENDING", "HELD", "APPROVED", "REJECTED", "FAILED"])
    var status: String? = null
    var memberId: UUID? = null
    var storyId: UUID? = null
    fun page(): AdminPagination {
        if(status != null && status !in setOf("PENDING","HELD","APPROVED","REJECTED","FAILED")) badAdminQuery("제출본 상태가 올바르지 않습니다.")
        return pagination(mapOf("createdAt" to "s.created_at", "decidedAt" to "s.decided_at", "heldAt" to "s.held_at"), "createdAt")
    }
}
data class AdminSubmissionStory(val storyId: UUID?, val title: String)
data class AdminSubmissionSummary(val submissionId: UUID, val kind: SubmissionKind,
    @get:io.swagger.v3.oas.annotations.media.Schema(allowableValues = ["PENDING", "HELD", "APPROVED", "REJECTED", "FAILED"]) val status: String,
    val story: AdminSubmissionStory, val owner: AdminMemberSummary, val issueCount: Int, val issueRules: List<String>,
    val firstIssueReason: String?, val holdReason: String?, val errorCode: String?,
    @field:io.swagger.v3.oas.annotations.media.Schema(description = "선점과 재제출마다 증가하는 실행 구분 번호") val attempt: Int,
    val createdAt: Instant, val decidedAt: Instant?, val heldAt: Instant?)
data class AdminSubmissionFields(val title: String, val oneLineIntro: String?, val description: String?, val protagonistName: String?,
    val genres: List<String>, val visibility: String, val characterNames: List<String>)
enum class AdminSubmissionImageStatus { APPROVED, REJECTED, ERROR, PENDING }
data class AdminSubmissionImageResult(val path: String, val label: String, val moderationStatus: AdminSubmissionImageStatus, val errorCode: String?)
data class AdminSubmissionDetail(@get:JsonUnwrapped val summary: AdminSubmissionSummary, val fields: AdminSubmissionFields,
    val issues: List<ModerationIssue>, val imageResults: List<AdminSubmissionImageResult>, val retryCount: Int, val updatedAt: Instant)

/** 목록 검색·페이지·정렬은 DB에서 수행하고 폼 원문은 목록에서 읽지 않는다. */
@Repository
class AdminSubmissionReadRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) {
    private val title = "COALESCE(t.title, jsonb_extract_path_text(s.payload, 'title'))"
    private val from = "FROM story_submissions s JOIN users u ON u.id=s.user_id LEFT JOIN admin_accounts a ON a.user_id=u.id LEFT JOIN stories t ON t.id=s.story_id"
    private val select = """s.public_id, s.kind, s.status, s.issues, s.hold_reason, s.error_code, s.attempt, s.created_at, s.decided_at, s.held_at,
        t.public_id AS story_public_id, $title AS title, $MEMBER_COLUMNS"""
    private val rowMapper = org.springframework.jdbc.core.RowMapper { rs: java.sql.ResultSet, _: Int ->
        var node = mapper.readTree(rs.getString("issues")); if(node.isString) node=mapper.readTree(node.asText())
        val issues=mapper.treeToValue(node, Array<ModerationIssue>::class.java).toList()
        val held=rs.getString("status")=="PENDING" && rs.getTimestamp("held_at")!=null
        AdminSubmissionSummary(rs.getObject("public_id",UUID::class.java), SubmissionKind.valueOf(rs.getString("kind")), if(held) "HELD" else rs.getString("status"),
            AdminSubmissionStory(rs.getObject("story_public_id",UUID::class.java),rs.getString("title")), requireNotNull(memberSummary(rs)),
            issues.size,issues.map { it.rule }.distinct(),issues.firstOrNull()?.reason,if(held) rs.getString("hold_reason") else null,
            if(rs.getString("status")=="FAILED") rs.getString("error_code") else null,rs.getInt("attempt"),rs.instant("created_at")!!,rs.instant("decided_at"),rs.instant("held_at"))
    }
    fun list(request: AdminSubmissionListRequest): AdminPageResponse<AdminSubmissionSummary> {
        val page=request.page(); val q=AdminQuery()
        when(request.status) {
            "PENDING" -> q.add("s.status='PENDING' AND s.held_at IS NULL")
            "HELD" -> q.add("s.status='PENDING' AND s.held_at IS NOT NULL")
            else -> q.equal("s.status",request.status)
        }
        q.equal("u.public_id",request.memberId); q.equal("t.public_id",request.storyId); q.range("s.created_at",request.from,request.to)
        request.normalizedQuery()?.let { term ->
            q.params["term"]=term
            val text="POSITION(:term IN ${AdminQuery.normalized(title)}) > 0"
            val id=runCatching { UUID.fromString(term) }.getOrNull()?.takeIf { it.toString()==term }
            if(id==null) q.add(text) else { q.params["queryId"]=id; q.add("(s.public_id=:queryId OR $text)") }
        }
        return jdbc.adminPage(select,from,q,page,"s.public_id",rowMapper)
    }
    fun summary(id: UUID): AdminSubmissionSummary? = jdbc.query("SELECT $select $from WHERE s.public_id=:id",mapOf("id" to id),rowMapper).singleOrNull()
}
