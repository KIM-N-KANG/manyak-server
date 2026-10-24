package com.knk.manyak.notice.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class NoticeTag {
    SERVICE, UPDATE, MAINTENANCE, EVENT, PROMOTION;

    val pushKind: com.knk.manyak.push.dto.PushKind
        get() = when (this) {
            EVENT, PROMOTION -> com.knk.manyak.push.dto.PushKind.MARKETING
            else -> com.knk.manyak.push.dto.PushKind.SERVICE
        }
}

enum class NoticeStatus { DRAFT, PUBLISHED }

@Entity
@Table(name = "notices")
class Notice(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "public_id", nullable = false, unique = true) val publicId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 100) var title: String,
    @Column(nullable = false, columnDefinition = "text") var body: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var tag: NoticeTag,
    @Column(name = "notification_title", length = 100) var notificationTitle: String? = null,
    @Column(name = "notification_body", length = 300) var notificationBody: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) var status: NoticeStatus = NoticeStatus.DRAFT,
    @Column(name = "published_at") var publishedAt: Instant? = null,
    @Column(name = "notified_at") var notifiedAt: Instant? = null,
    @Column(name = "created_by_user_id", nullable = false) val createdByUserId: Long,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)
