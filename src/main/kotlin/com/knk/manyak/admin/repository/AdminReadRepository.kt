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

internal const val MEMBER_COLUMNS = """u.public_id AS member_id, u.nickname, u.status AS member_status, u.created_at AS member_created_at,
    CASE WHEN a.user_id IS NULL THEN FALSE ELSE TRUE END AS is_admin"""
internal fun memberSummary(rs: ResultSet): AdminMemberSummary? = rs.getObject("member_id", UUID::class.java)?.let {
    AdminMemberSummary(it, rs.getString("nickname"), UserStatus.valueOf(rs.getString("member_status")), rs.getBoolean("is_admin"), rs.instant("member_created_at")!!)
}
@Repository
class AdminReadRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) {
    private val chats = "(SELECT COUNT(*) FROM story_chats c WHERE c.user_id=u.id AND c.deleted_at IS NULL)"
    private val spent = "(SELECT COALESCE(SUM(-amount),0) FROM credit_transactions t WHERE t.user_id=u.id AND ${AdminQuery.SPENT})"
    private val paid = "(SELECT COALESCE(SUM(price_krw),0) FROM credit_orders o WHERE o.user_id=u.id AND o.status='COMPLETED' AND o.completed_at IS NOT NULL)"
    private val active = """GREATEST(
        (SELECT MAX(c.updated_at) FROM story_chats c WHERE c.user_id=u.id AND c.deleted_at IS NULL),
        (SELECT MAX(t.created_at) FROM credit_transactions t WHERE t.user_id=u.id),
        (SELECT MAX(s.created_at) FROM stories s WHERE s.user_id=u.id))"""

    fun members(request: AdminMembersRequest, page: AdminPagination, memberId: UUID? = null): AdminPageResponse<AdminMemberListResponse> {
        val q = AdminQuery()
        q.equal("u.public_id", memberId)
        q.equal("u.status", request.status?.name)
        if (request.excludeDeleted == true) q.add("u.status <> 'DELETED'")
        q.range("u.created_at", request.from, request.to)
        val search = request.normalizedQuery()
        if (search != null) {
            val uuid = runCatching { UUID.fromString(search) }.getOrNull()?.takeIf { it.toString() == search }
            if (uuid != null) q.equal("u.public_id", uuid) else {
                q.params["search"] = search
                q.params["nickname"] = com.knk.manyak.user.service.nicknameKeyOf(search)
                q.add("""((u.status <> 'DELETED' AND POSITION(:nickname IN REGEXP_REPLACE(LOWER(u.nickname), '\s+', '', 'g')) > 0)
                    OR EXISTS (SELECT 1 FROM social_accounts sa WHERE sa.user_id=u.id AND sa.deleted_at IS NULL AND LOWER(sa.email)=:search))""")
            }
        }
        val period = request.chatFrom != null || request.chatTo != null
        val chatQ = AdminQuery().apply { range("c.created_at", request.chatFrom, request.chatTo) }
        q.params.putAll(chatQ.params.mapKeys { "chat_${it.key}" })
        var chatWhere = chatQ.where()
        chatQ.params.keys.forEach { chatWhere = chatWhere.replace(":$it", ":chat_$it") }
        val periodChats = "(SELECT COUNT(*) FROM story_chats c WHERE c.user_id=u.id AND c.deleted_at IS NULL AND $chatWhere)"
        val count = if (period) periodChats else chats
        if (period) q.add("$count > 0")
        request.chatBucket?.let { val bounds = AdminQuery.memberBounds[it]; q.add("$count BETWEEN ${bounds.first} AND ${bounds.last}") }
        request.creditDirection?.let {
            val credit = AdminQuery().apply {
                add("t.user_id=u.id"); add(if (it == "granted") AdminQuery.GRANTED else AdminQuery.SPENT)
                range("t.created_at", request.creditDate, request.creditDate)
            }
            var sql = credit.where()
            credit.params.forEach { (key, value) -> sql = sql.replace(":$key", ":credit_$key"); q.params["credit_$key"] = value }
            q.add("EXISTS (SELECT 1 FROM credit_transactions t WHERE $sql)")
        }
        request.attendanceDate?.let {
            val attendance = AdminQuery().apply { range("t.created_at", it, it) }
            var sql = attendance.where()
            attendance.params.forEach { (key, value) -> sql = sql.replace(":$key", ":att_$key"); q.params["att_$key"] = value }
            q.add("EXISTS (SELECT 1 FROM credit_transactions t WHERE t.user_id=u.id AND CAST(t.reason AS VARCHAR)='ATTENDANCE_REWARD' AND $sql)")
        }
        val select = "$MEMBER_COLUMNS, $active AS last_active_at, $spent AS credits_spent, $paid AS paid_krw, $chats AS chat_count, ${if (period) periodChats else "NULL"} AS period_chat_count"
        return jdbc.adminPage(select, "FROM users u LEFT JOIN admin_accounts a ON a.user_id=u.id", q, page, "u.public_id", RowMapper { rs, _ ->
            val summary = memberSummary(rs)!!
            AdminMemberListResponse(summary.memberId, summary.nickname, summary.status, summary.isAdmin, summary.createdAt,
                rs.instant("last_active_at"), rs.getLong("credits_spent"), rs.getLong("paid_krw"), rs.getLong("chat_count"), rs.longOrNull("period_chat_count"))
        })
    }

    private val paymentColumns = """o.public_id, o.product_id, o.status, o.price_krw, o.credit_amount, o.completed_at, o.refunded_at, o.reversal_shortfall, $MEMBER_COLUMNS"""
    private val paymentMapper = RowMapper { rs: ResultSet, _: Int ->
        AdminPaymentOrderResponse(rs.getObject("public_id", UUID::class.java), memberSummary(rs)!!, rs.getString("product_id"),
            CreditOrderStatus.valueOf(rs.getString("status")), rs.getLong("price_krw"), rs.getLong("credit_amount"),
            rs.instant("completed_at")!!, rs.instant("refunded_at"), rs.longOrNull("reversal_shortfall"))
    }
    fun payments(request: AdminPaymentsRequest, page: AdminPagination): AdminPageResponse<AdminPaymentOrderResponse> {
        val q = AdminQuery()
        q.add("o.completed_at IS NOT NULL AND o.status IN ('COMPLETED','REFUNDED')")
        q.equal("o.status", request.status?.name)
        q.equal("u.public_id", request.memberId)
        q.range("o.completed_at", request.from, request.to)
        q.search(request.normalizedQuery(), listOf("o.public_id", "u.public_id"), listOf("u.nickname"))
        return jdbc.adminPage(paymentColumns, "FROM credit_orders o JOIN users u ON u.id=o.user_id LEFT JOIN admin_accounts a ON a.user_id=u.id",
            q, page, "o.public_id", paymentMapper)
    }
    fun payment(orderId: UUID): AdminPaymentOrderResponse? = jdbc.query("""SELECT $paymentColumns FROM credit_orders o JOIN users u ON u.id=o.user_id
        LEFT JOIN admin_accounts a ON a.user_id=u.id WHERE o.public_id=:id AND o.completed_at IS NOT NULL AND o.status IN ('COMPLETED','REFUNDED')""",
        mapOf("id" to orderId), paymentMapper).firstOrNull()

    fun firstPaidAt(memberId: UUID): Instant = jdbc.queryForObject("""SELECT MIN(o.completed_at) FROM credit_orders o JOIN users u ON u.id=o.user_id
        WHERE u.public_id=:id AND o.completed_at IS NOT NULL AND o.status IN ('COMPLETED','REFUNDED')""", mapOf("id" to memberId), java.sql.Timestamp::class.java)!!.toInstant()

    fun paymentUsage(userId: Long, first: Instant): Pair<AdminPaymentUsage, AdminPaymentUsage> {
        val rows = jdbc.query("""SELECT CASE WHEN created_at < :first THEN 0 ELSE 1 END AS period,
            SUM(CASE WHEN CAST(reason AS VARCHAR)='CHAT_TURN' THEN 1 ELSE 0 END) AS turns,
            SUM(CASE WHEN CAST(reason AS VARCHAR)='STORY_CREATION' THEN 1 ELSE 0 END) AS stories,
            ABS(SUM(CASE WHEN CAST(reason AS VARCHAR) IN ('CHAT_TURN','STORY_CREATION','CHAT_IMAGE') THEN amount ELSE 0 END))
            - SUM(CASE WHEN CAST(reason AS VARCHAR)='REFUND' THEN amount ELSE 0 END) AS spent
            FROM credit_transactions WHERE user_id=:id GROUP BY 1""",
            mapOf("id" to userId, "first" to first.atOffset(ZoneOffset.UTC)), RowMapper { rs, _ ->
                rs.getInt("period") to AdminPaymentUsage(rs.getLong("turns"), rs.getLong("stories"), rs.getLong("spent"))
            }).toMap()
        return (rows[0] ?: AdminPaymentUsage(0, 0, 0)) to (rows[1] ?: AdminPaymentUsage(0, 0, 0))
    }

    fun transactions(userId: Long, page: AdminPagination): AdminPageResponse<AdminTransactionResponse> {
        val q = AdminQuery().apply { equal("user_id", userId) }
        // 누계를 전체 회원 원장에 먼저 적용하고 바깥에서 페이징한다. 동시각은 내부 기록 순서를 유지한다.
        val from = """FROM (SELECT id, amount, reason, created_at, user_id,
            SUM(amount) OVER (ORDER BY created_at ASC, id ASC ROWS UNBOUNDED PRECEDING) AS balance_after
            FROM credit_transactions WHERE user_id=:p0) t"""
        return jdbc.adminPage("amount, reason, balance_after, created_at", from, q, page, "id", RowMapper { rs, _ ->
            AdminTransactionResponse(rs.getLong("amount"), rs.getString("reason"), rs.getLong("balance_after"), rs.instant("created_at")!!)
        })
    }
    fun attendance(userId: Long, page: AdminPagination): AdminPageResponse<AdminAttendanceResponse> {
        val q = AdminQuery().apply { params["user"] = userId }
        val date = "CAST(created_at AT TIME ZONE 'Asia/Seoul' AS DATE)"
        return jdbc.adminPage("attendance_date, credit_amount", """FROM (SELECT $date AS attendance_date, SUM(amount) AS credit_amount
            FROM credit_transactions WHERE user_id=:user AND CAST(reason AS VARCHAR)='ATTENDANCE_REWARD' GROUP BY $date) t""",
            q, page, "attendance_date", RowMapper { rs, _ -> AdminAttendanceResponse(rs.getDate("attendance_date").toLocalDate(), rs.getLong("credit_amount")) })
    }

    fun auditLogs(request: AdminAuditLogsRequest, page: AdminPagination): AdminPageResponse<AdminAuditLogResponse> {
        val q = AdminQuery()
        q.equal("u.public_id", request.adminId); q.equal("l.action", request.action?.name)
        q.equal("l.target_type", request.targetType?.name); q.equal("l.target_id", request.targetId); q.equal("l.category", request.category?.name)
        q.range("l.created_at", request.from, request.to)
        q.search(request.normalizedQuery(), listOf("l.public_id"), listOf("u.nickname", "l.action", "l.reason"), listOf("l.target_id"))
        return jdbc.adminPage("l.public_id, l.category, l.action, l.target_type, l.target_id, l.reason, l.before_state, l.after_state, l.request_id, l.created_at, $MEMBER_COLUMNS",
            "FROM admin_audit_logs l JOIN users u ON u.id=l.admin_user_id LEFT JOIN admin_accounts a ON a.user_id=u.id", q, page, "l.public_id", RowMapper { rs, _ ->
                AdminAuditLogResponse(rs.getObject("public_id", UUID::class.java), memberSummary(rs)!!, rs.getString("category"), rs.getString("action"),
                    rs.getString("target_type"), rs.getString("target_id"), rs.getString("reason"), rs.getString("before_state")?.let(mapper::readTree),
                    rs.getString("after_state")?.let(mapper::readTree), rs.getString("request_id"), rs.instant("created_at")!!)
            })
    }
    fun contentCount(table: String, userId: Long): Long {
        require(table in setOf("stories", "story_chats"))
        return jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id=:id AND deleted_at IS NULL", mapOf("id" to userId), Long::class.java)!!
    }
}
