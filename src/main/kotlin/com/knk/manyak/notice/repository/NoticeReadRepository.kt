package com.knk.manyak.notice.repository

import com.knk.manyak.notice.dto.*
import com.knk.manyak.notice.entity.NoticeStatus
import com.knk.manyak.notice.entity.NoticeTag
import com.knk.manyak.notice.service.NoticeCursor
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException
import java.sql.ResultSet
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

internal data class PublicNoticeRow(val item: PublicNoticeItemResponse, val cursor: NoticeCursor)
@Repository
class NoticeReadRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val columns = "n.public_id,n.title,n.tag,n.status,n.published_at,n.notified_at,n.created_at,n.updated_at,u.public_id AS author_id,u.nickname"
    private val from = "FROM notices n JOIN users u ON u.id=n.created_by_user_id"
    private fun item(rs: ResultSet) = AdminNoticeItemResponse(rs.getObject("public_id", UUID::class.java), rs.getString("title"), NoticeTag.valueOf(rs.getString("tag")),
        NoticeStatus.valueOf(rs.getString("status")), rs.getTimestamp("published_at")?.toInstant(), rs.getTimestamp("notified_at")?.toInstant(),
        NoticeAuthorResponse(rs.getObject("author_id", UUID::class.java), rs.getString("nickname")), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant())
    fun detail(id: UUID): AdminNoticeResponse? = jdbc.query("SELECT $columns,n.body,n.notification_title,n.notification_body $from WHERE n.public_id=:id", mapOf("id" to id),
        RowMapper { rs, _ -> item(rs).let { AdminNoticeResponse(it.noticeId, it.title, it.tag, rs.getString("body"), it.status,
            it.publishedAt, it.notifiedAt, it.createdBy, it.createdAt, it.updatedAt, rs.getString("notification_title"), rs.getString("notification_body")) } }).firstOrNull()
    fun list(request: AdminNoticesRequest): AdminNoticePageResponse {
        val fields = mapOf("createdAt" to "n.created_at", "updatedAt" to "n.updated_at", "publishedAt" to "n.published_at", "notifiedAt" to "n.notified_at", "title" to "n.title", "status" to "n.status")
        val column = fields[request.sort] ?: badRequest()
        if (request.cursor != null || request.limit != null || request.page < 1 || request.size !in 1..100 || request.order !in setOf("asc", "desc")) badRequest()
        val params = mutableMapOf<String, Any>()
        val conditions = mutableListOf("1=1")
        request.status?.let { params["status"] = it.name; conditions += "n.status=:status" }
        request.q?.let {
            val q = it.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            if (q.isEmpty() || it.length > 50) badRequest()
            val uuid = runCatching { UUID.fromString(q) }.getOrNull()?.takeIf { it.toString() == q }
            params["q"] = q
            val titleMatch = "POSITION(:q IN LOWER(TRIM(REGEXP_REPLACE(n.title, '\\s+', ' ', 'g'))))>0"
            if (uuid != null) { params["id"] = uuid; conditions += "(n.public_id=:id OR $titleMatch)" }
            else conditions += titleMatch
        }
        val where = conditions.joinToString(" AND ")
        val count = jdbc.queryForObject("SELECT COUNT(*) $from WHERE $where", params, Long::class.java)!!
        val offset = (request.page.toLong() - 1) * request.size
        val rows = if (offset >= count) emptyList() else jdbc.query("SELECT $columns $from WHERE $where ORDER BY $column ${request.order} NULLS LAST,n.public_id ASC LIMIT :size OFFSET :offset",
            params + mapOf("size" to request.size, "offset" to offset), RowMapper { rs, _ -> item(rs) })
        return AdminNoticePageResponse(rows, request.page, request.size, count, (count + request.size - 1) / request.size)
    }
    internal fun published(limit: Int, cursor: NoticeCursor?, tag: NoticeTag?): List<PublicNoticeRow> {
        val params = mutableMapOf<String, Any>("limit" to limit)
        val tagCondition = tag?.let { params["tag"] = it.name; "AND tag=:tag" } ?: ""
        val condition = cursor?.let {
            params["at"] = it.publishedAt.atOffset(ZoneOffset.UTC); params["id"] = it.id
            "AND (published_at < :at OR (published_at = :at AND id < :id))"
        } ?: ""
        return jdbc.query("SELECT id,public_id,title,tag,published_at FROM notices WHERE status='PUBLISHED' $tagCondition $condition ORDER BY published_at DESC,id DESC LIMIT :limit", params, RowMapper { rs, _ ->
            val at = rs.getTimestamp("published_at").toInstant()
            PublicNoticeRow(PublicNoticeItemResponse(rs.getObject("public_id", UUID::class.java), rs.getString("title"), NoticeTag.valueOf(rs.getString("tag")), at), NoticeCursor(at, rs.getLong("id")))
        })
    }
    private fun badRequest(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어와 페이지·정렬 값을 확인하세요.")
}
