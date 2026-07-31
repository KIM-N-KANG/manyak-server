package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialProvider
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Schema(description = "계정 연동 요청(로그인된 세션에서 다른 소셜 provider를 같은 계정에 추가)")
data class AccountLinkRequest(
    @field:Schema(
        description = "연동할 provider. 로그인 경로가 있는 provider만 허용한다(GOOGLE·KAKAO).",
        example = "KAKAO",
    )
    val provider: SocialProvider,

    @field:NotBlank
    @field:Schema(description = "연동할 provider가 발급한 OIDC ID 토큰(JWT).")
    val idToken: String,

    @field:Valid
    @field:Schema(description = "재인증 정보. 이미 연동된 provider로 한 번 더 인증해야 연동이 진행된다.")
    val reauth: SocialReauthRequest,
)

@Schema(description = "재인증 정보(이미 연동된 provider)")
data class SocialReauthRequest(
    @field:Schema(description = "재인증에 쓸 provider. 요청자에게 이미 연동돼 있어야 한다.", example = "GOOGLE")
    val provider: SocialProvider,

    @field:NotBlank
    @field:Schema(
        description = "재인증용 ID 토큰. 방금 발급받은 토큰이어야 한다(오래된 토큰은 거부 — 공용 기기 보호).",
    )
    val idToken: String,
)

@Schema(description = "계정 연동 상태")
data class AccountLinkResponse(
    @field:Schema(description = "로그인 가능한 provider별 연동 상태. 연동 여부와 무관하게 전부 내려준다.")
    val links: List<AccountLinkItem>,
)

@Schema(description = "provider별 연동 상태")
data class AccountLinkItem(
    @field:Schema(description = "소셜 provider", example = "GOOGLE")
    val provider: SocialProvider,

    @field:Schema(description = "연동 여부", example = "true")
    val linked: Boolean,

    @field:Schema(description = "연동 시각. 연동돼 있지 않으면 null이다.", nullable = true)
    val connectedAt: Instant?,
)
