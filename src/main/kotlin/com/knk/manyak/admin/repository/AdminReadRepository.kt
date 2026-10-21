package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.*
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.credit.entity.CreditOrderStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Repository
class AdminReadRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) {
    private val memberColumns = """u.public_id AS member_id, u.nickname, u.status AS member_status, u.created_at AS member_created_at,
        CASE WHEN a.user_id IS NULL THEN FALSE ELSE TRUE END AS is_admin"""
    private val memberMapper = RowMapper { rs: ResultSet, _: Int -> summary(rs) }
    private fun summary(rs: ResultSet) = AdminMemberSummary(rs.getObject("member_id", UUID::class.java), rs.getString("nickname"),
        UserStatus.valueOf(rs.getString("member_status")), rs.getBoolean("is_admin"), rs.getTimestamp("member_created_at").toInstant())

    fun members(query: String?, status: UserStatus?, page: AdminPagination): AdminPageResponse<AdminMemberSummary> {
        val params = mutableMapOf<String, Any>()
        query?.let { params["query"] = it }
        val conditions = mutableListOf<String>()
        val uuid = runCatching { UUID.fromString(query) }.getOrNull()?.takeIf { it.toString().equals(query, true) }
        when {
            query == null -> conditions += "1=1"
            uuid != null -> { conditions += "u.public_id = :memberId"; params["memberId"] = uuid }
            query.contains('@') -> conditions += """EXISTS (SELECT 1 FROM social_accounts s WHERE s.user_id = u.id
                AND s.deleted_at IS NULL AND s.email = :query)"""
            else -> {
                params["nicknameKey"] = com.knk.manyak.user.service.nicknameKeyOf(query)
                conditions += "u.status <> 'DELETED' AND POSITION(:nicknameKey IN REPLACE(LOWER(u.nickname), ' ', '')) > 0"
            }
        }
        status?.let { conditions += "u.status = :status"; params["status"] = it.name }
        val from = "FROM users u LEFT JOIN admin_accounts a ON a.user_id = u.id WHERE ${conditions.joinToString(" AND ")}"
        return pageQuery("SELECT $memberColumns $from", "SELECT COUNT(*) $from", params, page, "u.public_id", memberMapper)
    }

    fun payments(request: AdminPaymentsRequest, range: AdminDateRange, page: AdminPagination): AdminPageResponse<AdminPaymentOrderResponse> {
        val params = mutableMapOf<String, Any>()
        val conditions = mutableListOf("o.completed_at IS NOT NULL", "o.status IN ('COMPLETED', 'REFUNDED')")
        request.status?.let { conditions += "o.status = :status"; params["status"] = it.name }
        range.from?.let { conditions += "o.completed_at >= :from"; params["from"] = it.atOffset(ZoneOffset.UTC) }
        range.until?.let { conditions += "o.completed_at < :until"; params["until"] = it.atOffset(ZoneOffset.UTC) }
        val from = """FROM credit_orders o JOIN users u ON u.id = o.user_id
            LEFT JOIN admin_accounts a ON a.user_id = u.id WHERE ${conditions.joinToString(" AND ")}"""
        return pageQuery("SELECT o.*, $memberColumns $from", "SELECT COUNT(*) $from", params, page, "o.public_id", RowMapper { rs, _ ->
            AdminPaymentOrderResponse(rs.getObject("public_id", UUID::class.java), summary(rs), rs.getString("product_id"),
                CreditOrderStatus.valueOf(rs.getString("status")), rs.getLong("price_krw"), rs.getLong("credit_amount"),
                rs.getTimestamp("completed_at").toInstant(), rs.getTimestamp("refunded_at")?.toInstant(),
                rs.getObject("reversal_shortfall")?.let { (it as Number).toLong() })
        })
    }

    fun auditLogs(request: AdminAuditLogsRequest, page: AdminPagination): AdminPageResponse<AdminAuditLogResponse> {
        val params = mutableMapOf<String, Any>()
        val conditions = mutableListOf("1=1")
        request.adminId?.let { conditions += "u.public_id = :adminId"; params["adminId"] = it }
        request.action?.let { conditions += "l.action = :action"; params["action"] = it.name }
        request.targetType?.let { conditions += "l.target_type = :targetType"; params["targetType"] = it.name }
        request.targetId?.let { conditions += "l.target_id = :targetId"; params["targetId"] = it }
        val from = """FROM admin_audit_logs l JOIN users u ON u.id = l.admin_user_id
            LEFT JOIN admin_accounts a ON a.user_id = u.id WHERE ${conditions.joinToString(" AND ")}"""
        return pageQuery("SELECT l.*, $memberColumns $from", "SELECT COUNT(*) $from", params, page, "l.public_id", RowMapper { rs, _ ->
            AdminAuditLogResponse(rs.getObject("public_id", UUID::class.java), summary(rs), rs.getString("category"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("reason"),
                rs.getString("before_state")?.let(mapper::readTree), rs.getString("after_state")?.let(mapper::readTree),
                rs.getString("request_id"), rs.getTimestamp("created_at").toInstant())
        })
    }

    fun contentCount(table: String, userId: Long): Long {
        require(table in setOf("stories", "story_chats"))
        return jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id = :id AND deleted_at IS NULL", mapOf("id" to userId), Long::class.java)!!
    }

    private fun <T> pageQuery(select: String, count: String, params: Map<String, Any>, page: AdminPagination, idColumn: String,
                              rowMapper: RowMapper<T>): AdminPageResponse<T> {
        val total = jdbc.queryForObject(count, params, Long::class.java)!!
        if (page.offset >= total) return page.response(emptyList(), total)
        val rows = jdbc.query("$select ORDER BY ${page.orderBy}, $idColumn ASC LIMIT :size OFFSET :offset",
            params + mapOf("size" to page.size, "offset" to page.offset), rowMapper)
        return page.response(rows, total)
    }
}
