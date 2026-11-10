package com.knk.manyak.admin.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.admin.repository.instant
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class AdminMemberSummary(
    val memberId: UUID,
    val nickname: String,
    val status: UserStatus,
    // springdoc이 is 접두를 벗겨 admin으로 문서화하므로 런타임과 같은 와이어 필드명을 JsonProperty로 고정한다(isOwner와 동일).
    @get:JsonProperty("isAdmin")
    val isAdmin: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User, isAdmin: Boolean) = AdminMemberSummary(user.publicId, user.nickname, user.status, isAdmin, user.createdAt)
    }
}
internal const val MEMBER_COLUMNS = """u.public_id AS member_id, u.nickname, u.status AS member_status, u.created_at AS member_created_at,
    CASE WHEN a.user_id IS NULL THEN FALSE ELSE TRUE END AS is_admin"""
internal fun memberSummary(rs: ResultSet): AdminMemberSummary? = rs.getObject("member_id", UUID::class.java)?.let {
    AdminMemberSummary(it, rs.getString("nickname"), UserStatus.valueOf(rs.getString("member_status")), rs.getBoolean("is_admin"), rs.instant("member_created_at")!!)
}
