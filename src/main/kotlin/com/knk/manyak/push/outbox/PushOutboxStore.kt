package com.knk.manyak.push.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

data class PushOutboxRow(val id: Long, val message: PushMessage, val attempts: Int, val createdAt: Instant, val leaseUntil: Instant)

@Repository
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class PushOutboxStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun insert(message: PushMessage, now: Instant) {
        jdbc.update("""
            INSERT INTO push_outbox(message_id, payload, status, attempts, next_attempt_at, created_at)
            VALUES (?, CAST(? AS jsonb), 'PENDING', 0, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
        """.trimIndent(), message.messageId, mapper.writeValueAsString(message), Timestamp.from(now), Timestamp.from(now))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(now: Instant, lease: Duration, batchSize: Int): List<PushOutboxRow> {
        val until = now.plus(lease)
        val rows = jdbc.query("""
            SELECT id, payload, attempts, created_at FROM push_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= ?
            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
        """.trimIndent(), { rs, _ ->
            PushOutboxRow(rs.getLong("id"), mapper.readValue(rs.getString("payload"), PushMessage::class.java),
                rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(), until)
        }, Timestamp.from(now), batchSize)
        rows.forEach { jdbc.update("UPDATE push_outbox SET next_attempt_at = ? WHERE id = ?", Timestamp.from(until), it.id) }
        return rows
    }

    // 임대가 만료되어 다른 릴레이가 재선점한 행에 늦은 결과를 덮어쓰지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(row: PushOutboxRow, now: Instant): Int = jdbc.update("""
        UPDATE push_outbox SET status = 'PUBLISHED', published_at = ?
        WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?
    """.trimIndent(), Timestamp.from(now), row.id, Timestamp.from(row.leaseUntil))

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(row: PushOutboxRow, next: Instant, abandoned: Boolean): Int = jdbc.update("""
        UPDATE push_outbox SET status = ?, attempts = attempts + 1, next_attempt_at = ?
        WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?
    """.trimIndent(), if (abandoned) "FAILED" else "PENDING", Timestamp.from(next), row.id, Timestamp.from(row.leaseUntil))
}
