package com.knk.manyak.admin.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class AdminAuditCategory { CHANGE, ACCESS }
enum class AdminAuditTargetType { MEMBER_QUERY, MEMBER, CREDIT_POLICY, PUSH_CAMPAIGN, PUSH_TEMPLATE, NOTICE, SUBMISSION, STORY }
enum class AdminAuditAction(val category: AdminAuditCategory, val targetType: AdminAuditTargetType) {
    MEMBER_SEARCHED(AdminAuditCategory.ACCESS, AdminAuditTargetType.MEMBER_QUERY),
    MEMBER_VIEWED(AdminAuditCategory.ACCESS, AdminAuditTargetType.MEMBER),
    MEMBER_SUSPENDED(AdminAuditCategory.CHANGE, AdminAuditTargetType.MEMBER),
    MEMBER_UNSUSPENDED(AdminAuditCategory.CHANGE, AdminAuditTargetType.MEMBER),
    CREDIT_GRANTED(AdminAuditCategory.CHANGE, AdminAuditTargetType.MEMBER),
    CREDIT_REVOKED(AdminAuditCategory.CHANGE, AdminAuditTargetType.MEMBER),
    CREDIT_POLICY_UPSERTED(AdminAuditCategory.CHANGE, AdminAuditTargetType.CREDIT_POLICY),
    CREDIT_POLICY_DELETED(AdminAuditCategory.CHANGE, AdminAuditTargetType.CREDIT_POLICY),
    PUSH_CAMPAIGN_CREATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.PUSH_CAMPAIGN),
    PUSH_CAMPAIGN_CANCELED(AdminAuditCategory.CHANGE, AdminAuditTargetType.PUSH_CAMPAIGN),
    PUSH_CAMPAIGN_RETRIED(AdminAuditCategory.CHANGE, AdminAuditTargetType.PUSH_CAMPAIGN),
    PUSH_TEMPLATE_UPSERTED(AdminAuditCategory.CHANGE, AdminAuditTargetType.PUSH_TEMPLATE),
    PUSH_TEMPLATE_RESET(AdminAuditCategory.CHANGE, AdminAuditTargetType.PUSH_TEMPLATE),
    NOTICE_CREATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.NOTICE),
    NOTICE_UPDATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.NOTICE),
    NOTICE_PUBLISHED(AdminAuditCategory.CHANGE, AdminAuditTargetType.NOTICE),
    NOTICE_UNPUBLISHED(AdminAuditCategory.CHANGE, AdminAuditTargetType.NOTICE),
    NOTICE_DELETED(AdminAuditCategory.CHANGE, AdminAuditTargetType.NOTICE),
    ORIGINAL_CREATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.STORY),
    ORIGINAL_UPDATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.STORY),
    ORIGINAL_THUMBNAIL_DELETED(AdminAuditCategory.CHANGE, AdminAuditTargetType.STORY),
    SUBMISSION_HOLD_RELEASED(AdminAuditCategory.CHANGE, AdminAuditTargetType.SUBMISSION),
    ORIGINAL_CREATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.STORY),
    ORIGINAL_UPDATED(AdminAuditCategory.CHANGE, AdminAuditTargetType.STORY),
    ORIGINAL_THUMBNAIL_DELETED(AdminAuditCategory.CHANGE, AdminAuditTargetType.STORY),
}

@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "public_id", nullable = false, unique = true, updatable = false) val publicId: UUID = UUID.randomUUID(),
    @Column(name = "admin_user_id", nullable = false, updatable = false) val adminUserId: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10, updatable = false) val category: AdminAuditCategory,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50, updatable = false) val action: AdminAuditAction,
    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false, length = 30, updatable = false) val targetType: AdminAuditTargetType,
    @Column(name = "target_id", nullable = false, length = 255, updatable = false) val targetId: String,
    @Column(length = 500, updatable = false) val reason: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_state", columnDefinition = "jsonb", updatable = false) val beforeState: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_state", columnDefinition = "jsonb", updatable = false) val afterState: String? = null,
    @Column(name = "request_id", nullable = false, length = 64, updatable = false) val requestId: String,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
