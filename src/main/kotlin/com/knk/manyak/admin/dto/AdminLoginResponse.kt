package com.knk.manyak.admin.dto

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import java.time.Instant
import java.util.UUID

data class AdminMemberSummary(
    val memberId: UUID,
    val nickname: String,
    val status: UserStatus,
    val isAdmin: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User, isAdmin: Boolean) = AdminMemberSummary(user.publicId, user.nickname, user.status, isAdmin, user.createdAt)
    }
}

data class AdminLoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val admin: AdminMemberSummary,
    val tokenType: String = "Bearer",
)
