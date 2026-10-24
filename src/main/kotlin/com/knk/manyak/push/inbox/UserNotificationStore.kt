package com.knk.manyak.push.inbox

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class NotificationCursor(val id: Long, val createdAt: Instant)

@Repository
class UserNotificationStore(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun insert(row: UserNotification): Int = jdbc.update("""
        INSERT INTO user_notifications(public_id, user_id, message_key, type, category, title, body,
            deep_link, story_public_id, notice_public_id, notice_tag, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
    """.trimIndent(), row.publicId, row.userId, row.messageKey, row.type.name, row.category.name,
        row.title, row.body, row.deepLink, row.storyPublicId, row.noticePublicId, row.noticeTag?.name, Timestamp.from(row.createdAt))

    /** 대상 결정과 삽입은 한 문장. 토큰·서비스 설정·야간 동의는 내역의 자격이 아니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun insertScheduled(data: NotificationContent, scenario: String, occurrence: String, now: Instant): Int {
        val (key, eligibility) = when (scenario) {
            "attendance" -> "'attendance:' || u.public_id || ':' || ?" to """
                AND u.marketing_push_agreed_at IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM credit_transactions c WHERE c.idempotency_key =
                    'attendance:' || COALESCE(u.reward_identity_user_id, u.id) || ':' || ?)
            """.trimIndent()
            "notice" -> "'notice:' || ? || ':' || u.public_id" to ""
            "promotion" -> "'promotion:' || ? || ':' || u.public_id" to "AND u.marketing_push_agreed_at IS NOT NULL"
            else -> error("Unknown scheduled notification scenario: $scenario")
        }
        val args = mutableListOf<Any?>(occurrence, data.type.name, data.type.category.name, data.title, data.body,
            data.deepLink, data.storyId, data.noticeId, data.noticeTag?.name, Timestamp.from(now))
        if (scenario == "attendance") args += occurrence
        // public_id도 DB에서 행마다 생성한다. ON CONFLICT는 회원+메시지 유니크 충돌 시 최초 사본을 보존한다.
        return jdbc.update("""
            INSERT INTO user_notifications(public_id, user_id, message_key, type, category, title, body,
                deep_link, story_public_id, notice_public_id, notice_tag, created_at)
            SELECT gen_random_uuid(), u.id, $key, ?, ?, ?, ?, ?, ?, ?, ?, ? FROM users u
            WHERE u.status = 'ACTIVE' $eligibility
            ON CONFLICT DO NOTHING
        """.trimIndent(), *args.toTypedArray())
    }

    fun cursor(userId: Long, publicId: UUID): NotificationCursor? = jdbc.query(
        "SELECT id, created_at FROM user_notifications WHERE user_id = ? AND public_id = ?",
        { rs, _ -> NotificationCursor(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()) }, userId, publicId,
    ).singleOrNull()

    fun list(userId: Long, category: NotificationCategory?, cursor: NotificationCursor?, limit: Int): List<NotificationItem> {
        val args = mutableListOf<Any>(userId)
        val conditions = buildString {
            if (category != null) { append(" AND category = ?"); args += category.name }
            if (cursor != null) {
                append(" AND (created_at, id) < (?, ?)")
                args += Timestamp.from(cursor.createdAt)
                args += cursor.id
            }
        }
        args += limit
        return jdbc.query("""SELECT * FROM user_notifications WHERE user_id = ? $conditions
            ORDER BY created_at DESC, id DESC LIMIT ?""", { rs, _ -> rs.toItem() }, *args.toTypedArray())
    }

    fun unreadCount(userId: Long): NotificationUnreadCount {
        val counts = jdbc.query("""SELECT category, COUNT(*) AS amount FROM user_notifications
            WHERE user_id = ? AND read_at IS NULL GROUP BY category""",
            { rs, _ -> rs.getString("category") to rs.getLong("amount") }, userId).toMap()
        val news = counts["NEWS"] ?: 0L
        val activity = counts["ACTIVITY"] ?: 0L
        return NotificationUnreadCount(news + activity, news, activity)
    }

    fun markRead(userId: Long, publicId: UUID, now: Instant): Int = jdbc.update("""
        UPDATE user_notifications SET read_at = ? WHERE user_id = ? AND public_id = ? AND read_at IS NULL
    """.trimIndent(), Timestamp.from(now), userId, publicId)

    fun markAllRead(userId: Long, category: NotificationCategory?, now: Instant): Int {
        val args = mutableListOf<Any>(Timestamp.from(now), userId)
        val condition = if (category != null) { args += category.name; " AND category = ?" } else ""
        return jdbc.update("UPDATE user_notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL$condition", *args.toTypedArray())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteForUser(userId: Long): Int = jdbc.update("DELETE FROM user_notifications WHERE user_id = ?", userId)

    private fun ResultSet.toItem() = NotificationItem(
        getObject("public_id", UUID::class.java), NotificationCategory.valueOf(getString("category")),
        NotificationType.valueOf(getString("type")), getString("title"), getString("body"), getString("deep_link"),
        getObject("story_public_id", UUID::class.java), getObject("notice_public_id", UUID::class.java),
        getString("notice_tag")?.let(com.knk.manyak.notice.entity.NoticeTag::valueOf),
        getTimestamp("created_at").toInstant(), getTimestamp("read_at")?.toInstant(),
    )
}

data class NotificationContent(
    val type: NotificationType, val title: String, val body: String, val deepLink: String?,
    val storyId: UUID? = null, val noticeId: UUID? = null, val noticeTag: com.knk.manyak.notice.entity.NoticeTag? = null,
)
