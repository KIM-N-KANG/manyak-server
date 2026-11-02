package com.knk.manyak.admin.repository

import com.knk.manyak.push.dto.PushMemberDelivery
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.temporal.ChronoUnit

data class PushDeliveryChatActivity(val delivery: PushMemberDelivery, val chatted: Boolean, val chattedBefore: Boolean)

/** NOTIFICATION이 제공한 도달 시각만 입력받는다. 발송 결과 테이블을 직접 읽지 않는다. */
@Repository
class PushChatConversionRepository(private val jdbc: JdbcTemplate) {
    fun activity(deliveries: List<PushMemberDelivery>): List<PushDeliveryChatActivity> = deliveries.chunked(500).flatMap { batch ->
        val params = mutableListOf<Any>()
        val values = batch.mapIndexed { index, row ->
            params.addAll(listOf(index, row.userId, Timestamp.from(row.completedAt.minus(24, ChronoUnit.HOURS)),
                Timestamp.from(row.completedAt), Timestamp.from(row.completedAt.plus(24, ChronoUnit.HOURS))))
            "(CAST(? AS INTEGER),CAST(? AS BIGINT),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))"
        }.joinToString(",")
        // [도달 시각, 24시간 뒤), [24시간 전, 도달 시각). 메시지 원문은 선택하지 않는다.
        fun exists(from: String, to: String) = """EXISTS (SELECT 1 FROM story_chats c JOIN story_messages m ON m.chat_id=c.id
            WHERE c.user_id=d.user_id AND m.role='USER' AND m.created_at>=d.$from AND m.created_at<d.$to)"""
        jdbc.query("""SELECT d.row_index,${exists("delivered_at", "after_at")} AS chatted,
            ${exists("before_at", "delivered_at")} AS chatted_before
            FROM (VALUES $values) d(row_index,user_id,before_at,delivered_at,after_at)""",
            { rs, _ -> PushDeliveryChatActivity(batch[rs.getInt("row_index")], rs.getBoolean("chatted"), rs.getBoolean("chatted_before")) },
            *params.toTypedArray())
    }
}
