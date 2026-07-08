package com.knk.manyak.auth.dto

import com.knk.manyak.auth.entity.UserStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "토큰 발급/회전 응답")
data class TokenResponse(
    @field:Schema(
        description = "access 토큰(HS256 JWT). Authorization: Bearer <accessToken> 로 보낸다.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIzZjI1MDRlMC00Zjg5LTQxZDMtOWEwYy0wMzA1ZTgyYzMzMDEifQ.signature",
    )
    val accessToken: String,

    @field:Schema(
        description = "refresh 토큰(불투명 랜덤 문자열). access 만료 시 /token/refresh 로 회전한다. 1회용이며 회전 후 폐기된다.",
        example = "Qm9vdHN0cmFwUmVmcmVzaFRva2VuRXhhbXBsZVZhbHVlMTIz",
    )
    val refreshToken: String,

    @field:Schema(description = "access 토큰 만료까지 남은 시간(초)", example = "1800")
    val expiresIn: Long,

    @field:Schema(description = "토큰 타입. 항상 Bearer.", example = "Bearer")
    val tokenType: String = "Bearer",
)

@Schema(description = "Google 로그인 요청")
data class GoogleLoginRequest(
    @field:NotBlank
    @field:Schema(
        description = "Google에서 발급받은 ID 토큰(JWT). 서버가 Google 공개키로 검증한다.",
        example = "eyJhbGciOiJSUzI1NiIsImtpZCI6IjEyMyJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMTAxNjkifQ.signature",
    )
    val idToken: String,

    @field:Schema(
        description = "초대 코드(선택). 최초 가입 시 함께 보내면 초대자·피초대자 양쪽에 크레딧을 적립한다. " +
            "미해결·자기 코드·이미 가입된 계정의 제출은 오류 없이 무시된다.",
        example = "Ab3Xk9Qz",
        nullable = true,
    )
    val inviteCode: String? = null,
)

@Schema(description = "refresh 토큰 회전 요청")
data class RefreshTokenRequest(
    @field:NotBlank
    @field:Schema(
        description = "발급받은 refresh 토큰",
        example = "Qm9vdHN0cmFwUmVmcmVzaFRva2VuRXhhbXBsZVZhbHVlMTIz",
    )
    val refreshToken: String,
)

@Schema(description = "로그아웃 요청")
data class LogoutRequest(
    @field:NotBlank
    @field:Schema(
        description = "폐기할 refresh 토큰. 단일 기기 로그아웃으로 이 토큰만 폐기되어 재발급(회전)이 막힌다.",
        example = "Qm9vdHN0cmFwUmVmcmVzaFRva2VuRXhhbXBsZVZhbHVlMTIz",
    )
    val refreshToken: String,
)

@Schema(description = "현재 로그인 사용자 정보")
data class MeResponse(
    @field:Schema(description = "사용자 ID(공개 식별자)", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
    val id: String,

    @field:Schema(description = "닉네임", example = "manyak_user")
    val nickname: String,

    @field:Schema(description = "프로필 이미지 URL", example = "https://example.com/profile.png", nullable = true)
    val profileImageUrl: String?,

    @field:Schema(description = "계정 상태", example = "ACTIVE")
    val status: UserStatus,

    @field:Schema(description = "크레딧 잔액. 지갑이 없으면 0", example = "500")
    val creditBalance: Long,

    @field:Schema(description = "KST 자정 기준 당일 출석체크 적립 완료 여부", example = "false")
    val attendedToday: Boolean,
)
