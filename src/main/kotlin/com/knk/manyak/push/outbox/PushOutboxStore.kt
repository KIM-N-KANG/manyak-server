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

/** 도메인 기록은 기존 트랜잭션에 참여하고, 선점·발행 결과는 짧은 독립 트랜잭션으로 확정한다. */
@Repository
@ConditionalOnProperty(name = ["manyak.push.mode"], havingValue = "remote")
class PushOutboxStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    /** 기록만 별도로 커밋하면 완료 상태와 알림이 어긋나므로 트랜잭션 없는 호출은 거부한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun insert(message: PushMessage, now: Instant) {
        jdbc.update("""
            INSERT INTO push_outbox(message_id, payload, status, attempts, next_attempt_at, created_at)
            VALUES (?, CAST(? AS jsonb), 'PENDING', 0, ?, ?)
            ON CONFLICT (message_id) DO NOTHING
        """.trimIndent(), message.messageId, mapper.writeValueAsString(message), Timestamp.from(now), Timestamp.from(now))
    }

    /**
     * DB 잠금을 잡은 채 브로커를 기다리지 않도록 임대 시각을 먼저 커밋한다.
     * 프로세스가 발행 도중 종료되면 이 시각 이후 다른 릴레이가 다시 가져갈 수 있다.
     * SKIP LOCKED는 다른 인스턴스가 선점 중인 행을 기다리지 않고 건너뛴다.
     */
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

    /**
     * next_attempt_at = leaseUntil은 아직 이 선점의 결과인지 확인하는 조건이다.
     * 임대 만료 후 다른 릴레이가 재선점했다면 갱신하지 않아 새 시도의 결과를 보호한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(row: PushOutboxRow, now: Instant): Int = jdbc.update("""
        UPDATE push_outbox SET status = 'PUBLISHED', published_at = ?
        WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?
    """.trimIndent(), Timestamp.from(now), row.id, Timestamp.from(row.leaseUntil))

    /** 실패도 임대 소유를 확인한다. 이전 시도의 늦은 실패가 새 발행을 재시도로 되돌리면 안 된다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(row: PushOutboxRow, next: Instant, abandoned: Boolean): Int = jdbc.update("""
        UPDATE push_outbox SET status = ?, attempts = attempts + 1, next_attempt_at = ?
        WHERE id = ? AND status = 'PENDING' AND next_attempt_at = ?
    """.trimIndent(), if (abandoned) "FAILED" else "PENDING", Timestamp.from(next), row.id, Timestamp.from(row.leaseUntil))
}
