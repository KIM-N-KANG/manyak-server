package com.knk.manyak.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Google 로그인 요청")
data class GoogleLoginRequest(
    @field:NotBlank
    @field:Schema(
        description = "Google OAuth 인증 후 클라이언트가 전달하는 ID 토큰",
        example = "eyJhbGciOiJSUzI1NiIsImtpZCI6Ij...",
    )
    val idToken: String,
)

@Schema(description = "로그인 응답")
data class LoginResponse(
    @field:Schema(description = "서비스 액세스 토큰", example = "access-token")
    val accessToken: String,

    @field:Schema(description = "서비스 리프레시 토큰", example = "refresh-token")
    val refreshToken: String,

    @field:Schema(description = "토큰 타입", example = "Bearer")
    val tokenType: String = "Bearer",

    @field:Schema(description = "액세스 토큰 만료 시간(초)", example = "3600")
    val expiresIn: Long,

    @field:Schema(description = "로그인 사용자 정보")
    val user: LoginUserResponse,
)

@Schema(description = "로그인 사용자 정보")
data class LoginUserResponse(
    @field:Schema(description = "사용자 ID", example = "1")
    val id: Long,

    @field:Schema(description = "사용자 닉네임", example = "manyak_writer")
    val nickname: String,

    @field:Schema(description = "프로필 이미지 URL", example = "https://example.com/profile.png")
    val profileImageUrl: String?,
)
