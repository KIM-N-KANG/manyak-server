package com.knk.manyak.admin.repository

import com.knk.manyak.admin.dto.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.ZoneOffset

data class AdminPaymentPeriodTotals(
    val payingMembers: Long, val orders: Long, val amountKrw: Long,
    val firstTime: AdminPaymentTotals, val repeat: AdminPaymentTotals, val refundedOrders: Long,
)

@Repository
class AdminPaymentMetricsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    // 완료 시각만 판정한다. 환불된 첫 주문도 이력이며 같은 시각이면 내부 주문 ID가 작은 주문이 먼저다.
    private val firstOrder = """NOT EXISTS (SELECT 1 FROM credit_orders previous
        WHERE previous.user_id=o.user_id AND previous.completed_at IS NOT NULL
        AND (previous.completed_at<o.completed_at OR (previous.completed_at=o.completed_at AND previous.id<o.id)))"""
    private fun owner(column: String, excluded: List<Long>) =
        if (excluded.isEmpty()) "1=1" else "($column IS NULL OR $column NOT IN (:excluded))"
    private fun period(excluded: List<Long>) = "o.completed_at>=:from AND o.completed_at<=:until AND ${owner("o.user_id", excluded)}"
    private fun params(from: Instant, until: Instant, excluded: List<Long>): Map<String, Any> =
        mapOf("from" to from.atOffset(ZoneOffset.UTC), "until" to until.atOffset(ZoneOffset.UTC)) +
            if (excluded.isEmpty()) emptyMap() else mapOf("excluded" to excluded)

    fun activeMembers(from: Instant, until: Instant, excluded: List<Long>): Long = jdbc.queryForObject("""
        SELECT COUNT(DISTINCT c.user_id) FROM story_messages m JOIN story_chats c ON c.id=m.chat_id
        WHERE m.role='USER' AND m.created_at>=:from AND m.created_at<=:until
            AND c.user_id IS NOT NULL AND ${owner("c.user_id", excluded)}
        """, params(from, until, excluded), Long::class.java)!!

    fun totals(from: Instant, until: Instant, excluded: List<Long>): AdminPaymentPeriodTotals = jdbc.queryForObject("""
        SELECT COUNT(DISTINCT user_id) AS paying_members, COUNT(*) AS orders, COALESCE(SUM(price_krw),0) AS amount_krw,
            COALESCE(SUM(CASE WHEN is_first=1 THEN 1 ELSE 0 END),0) AS first_orders,
            COALESCE(SUM(CASE WHEN is_first=1 THEN price_krw ELSE 0 END),0) AS first_amount,
            COALESCE(SUM(CASE WHEN is_first=0 THEN 1 ELSE 0 END),0) AS repeat_orders,
            COALESCE(SUM(CASE WHEN is_first=0 THEN price_krw ELSE 0 END),0) AS repeat_amount,
            COALESCE(SUM(CASE WHEN status='REFUNDED' THEN 1 ELSE 0 END),0) AS refunded_orders
        FROM (SELECT o.user_id,o.price_krw,o.status,CASE WHEN $firstOrder THEN 1 ELSE 0 END AS is_first
            FROM credit_orders o WHERE ${period(excluded)}) period_orders
        """, params(from, until, excluded)) { rs, _ -> AdminPaymentPeriodTotals(
        rs.getLong("paying_members"), rs.getLong("orders"), rs.getLong("amount_krw"),
        AdminPaymentTotals(rs.getLong("first_orders"), rs.getLong("first_amount")),
        AdminPaymentTotals(rs.getLong("repeat_orders"), rs.getLong("repeat_amount")), rs.getLong("refunded_orders")) }!!

    fun products(from: Instant, until: Instant, excluded: List<Long>): List<AdminPaymentProductMetrics> = jdbc.query("""
        SELECT o.product_id,COUNT(*) AS orders,SUM(o.price_krw) AS amount_krw,SUM(o.credit_amount) AS credit_amount
        FROM credit_orders o WHERE ${period(excluded)} GROUP BY o.product_id ORDER BY amount_krw DESC,o.product_id ASC
        """, params(from, until, excluded)) { rs, _ -> AdminPaymentProductMetrics(rs.getString("product_id"),
        rs.getLong("orders"), rs.getLong("amount_krw"), rs.getLong("credit_amount")) }

    fun daysToFirstPayment(from: Instant, until: Instant, excluded: List<Long>): List<AdminFirstPaymentBucket> {
        // KST 달력 날짜 차이. DB 세션 시간대와 경과 시간의 24시간 단위에 의존하지 않는다.
        val days = """FLOOR((EXTRACT(EPOCH FROM o.completed_at)+${AdminQuery.KST_OFFSET_SECONDS})/86400)
            - FLOOR((EXTRACT(EPOCH FROM u.created_at)+${AdminQuery.KST_OFFSET_SECONDS})/86400)"""
        val bucket = "CASE WHEN elapsed_days=0 THEN 0 WHEN elapsed_days<=3 THEN 1 WHEN elapsed_days<=7 THEN 2 WHEN elapsed_days<=30 THEN 3 ELSE 4 END"
        val rows = jdbc.query("""SELECT $bucket AS bucket,COUNT(*) AS members
            FROM (SELECT $days AS elapsed_days FROM credit_orders o JOIN users u ON u.id=o.user_id
                WHERE ${period(excluded)} AND $firstOrder) first_payments
            GROUP BY $bucket""", params(from, until, excluded)) { rs, _ -> rs.getInt("bucket") to rs.getLong("members") }.toMap()
        return listOf("0", "1~3", "4~7", "8~30", "31+").mapIndexed { index, label -> AdminFirstPaymentBucket(label, rows[index] ?: 0L) }
    }
}
