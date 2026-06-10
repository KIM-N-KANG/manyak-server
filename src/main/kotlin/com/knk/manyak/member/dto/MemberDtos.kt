package com.knk.manyak.member.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "내 정보 응답")
data class MyInfoResponse(
    @field:Schema(description = "사용자 ID", example = "1")
    val id: Long,

    @field:Schema(description = "이메일", example = "writer@example.com")
    val email: String,

    @field:Schema(description = "닉네임", example = "manyak_writer")
    val nickname: String,

    @field:Schema(description = "프로필 이미지 URL", example = "https://example.com/profile.png")
    val profileImageUrl: String?,

    @field:Schema(description = "가입 시각", example = "2026-06-10T12:00:00Z")
    val createdAt: Instant,
)
