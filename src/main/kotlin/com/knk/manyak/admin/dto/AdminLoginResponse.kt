package com.knk.manyak.admin.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
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

data class AdminLoginResponse(
    val accessToken: String,
    val expiresIn: Long,
    val admin: AdminMemberSummary,
    val tokenType: String = "Bearer",
)
