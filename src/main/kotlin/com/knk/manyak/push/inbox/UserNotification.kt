package com.knk.manyak.push.inbox

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class NotificationCategory { NEWS, ACTIVITY }

enum class NotificationType(val category: NotificationCategory) {
    STORY_COMPLETED(NotificationCategory.ACTIVITY),
    STORY_MODERATION_COMPLETED(NotificationCategory.ACTIVITY),
    STORY_TAKEN_DOWN(NotificationCategory.ACTIVITY),
    NOTICE(NotificationCategory.NEWS),
    ATTENDANCE_REMINDER(NotificationCategory.ACTIVITY),
    CREDIT_EXPIRY_REMINDER(NotificationCategory.ACTIVITY),
    PROMOTION(NotificationCategory.NEWS),
}

/** 기록 당시의 사본. 스토리·공지의 변경이나 삭제에 따라 갱신하지 않는다. */
@Entity
@Table(name = "user_notifications", uniqueConstraints = [UniqueConstraint(
    name = "uk_user_notifications_user_message", columnNames = ["user_id", "message_key"],
)])
class UserNotification(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "public_id", nullable = false, unique = true) val publicId: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Column(name = "message_key", nullable = false, length = 255) val messageKey: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) val type: NotificationType,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val category: NotificationCategory = type.category,
    @Column(nullable = false, length = 120) val title: String,
    @Column(nullable = false, length = 300) val body: String,
    @Column(name = "deep_link", length = 512) val deepLink: String? = null,
    @Column(name = "story_public_id") val storyPublicId: UUID? = null,
    @Column(name = "notice_public_id") val noticePublicId: UUID? = null,
    @Enumerated(EnumType.STRING) @Column(name = "notice_tag", length = 16) val noticeTag: com.knk.manyak.notice.entity.NoticeTag? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "read_at") val readAt: Instant? = null,
)

data class NotificationItem(
    val notificationId: UUID,
    val category: NotificationCategory,
    val type: NotificationType,
    val title: String,
    val body: String,
    val deepLink: String?,
    val storyId: UUID?,
    val noticeId: UUID?,
    val noticeTag: com.knk.manyak.notice.entity.NoticeTag?,
    val createdAt: Instant,
    val readAt: Instant?,
)
data class NotificationPage(val items: List<NotificationItem>, val nextCursor: UUID?)
data class NotificationUnreadCount(val total: Long, val news: Long, val activity: Long)
