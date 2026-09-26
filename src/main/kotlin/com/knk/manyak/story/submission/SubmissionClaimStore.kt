package com.knk.manyak.story.submission

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** 빈 실행 슬롯에 들어갈 행만 짧은 독립 트랜잭션으로 선점한다. */
@Repository
class SubmissionClaimStore(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(now: Instant, lease: Duration, limit: Int): List<SubmissionRequested> {
        if (limit <= 0) return emptyList()
        val rows = jdbc.query("""
            SELECT id, attempt FROM story_submissions
            WHERE status = 'PENDING' AND (dispatched_at IS NULL OR dispatched_at <= ?)
            ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
        """.trimIndent(), { rs, _ -> SubmissionRequested(rs.getLong("id"), rs.getInt("attempt") + 1) },
            Timestamp.from(now.minus(lease)), limit)
        rows.forEach { row -> jdbc.update("""
            UPDATE story_submissions SET dispatched_at = ?, updated_at = ?, attempt = ? WHERE id = ?
        """.trimIndent(), Timestamp.from(now), Timestamp.from(now), row.attempt, row.id) }
        return rows
    }

    /** 실행기 종료/거부로 시작하지 못한 작업은 임대를 반환한다. 재선점은 새 attempt를 받는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun release(row: SubmissionRequested) {
        jdbc.update("""UPDATE story_submissions SET dispatched_at = NULL
            WHERE id = ? AND status = 'PENDING' AND attempt = ?""", row.id, row.attempt)
    }
}
