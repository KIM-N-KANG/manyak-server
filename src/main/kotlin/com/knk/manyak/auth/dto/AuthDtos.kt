package com.knk.manyak.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty
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

    // springdoc 인트로스펙션(Jackson 2 빈 규약)은 is 접두를 떼고 newUser로 문서화하지만 런타임(Jackson 3
    // Kotlin 모듈)은 isNewUser로 직렬화하므로, 양쪽이 모두 읽는 JsonProperty로 와이어 필드명을 고정한다.
    @get:JsonProperty("isNewUser")
    @field:Schema(
        description = "이번 로그인으로 계정이 새로 생성됐는지(신규 가입 여부). 프론트엔드 신규 가입 온보딩" +
            "(초대 코드 입력 스텝, KNK-567)의 판정 신호다. 기존 계정 로그인과 refresh 회전은 항상 false.",
        example = "false",
    )
    val isNewUser: Boolean = false,
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
        description = "인앱 브라우저에서 만든 로그인 핸드오프 코드(스펙 §4-3-5). 유효하면 이 호출이 회원 체험 시드" +
            "(핸드오프의 원본 디바이스 ID가 X-Manyak-Device-Id 헤더보다 우선)와 게스트 데이터 이관을 함께 수행한다. " +
            "무효·만료면 헤더 디바이스 ID로 폴백하고 로그인은 정상 진행한다.",
        nullable = true,
    )
    val handoffCode: String? = null,
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

    @field:Schema(description = "프로필 이미지 URL(원본 전체 해상도)", example = "https://example.com/profile.png", nullable = true)
    val profileImageUrl: String?,

    @field:Schema(
        description = "48×48 저해상도 인라인 썸네일(base64). 세션 복원 시 이미지 호스트 왕복 없이 헤더 아바타 첫 페인트용. " +
            "미배정·미생성이면 null(스펙 §4-3-5 B17).",
        nullable = true,
    )
    val profileThumbnailBase64: String?,

    @field:Schema(description = "계정 상태", example = "ACTIVE")
    val status: UserStatus,

    @field:Schema(description = "크레딧 잔액. 지갑이 없으면 0", example = "500")
    val creditBalance: Long,

    @field:Schema(description = "KST 자정 기준 당일 출석체크 적립 완료 여부", example = "false")
    val attendedToday: Boolean,
)
