package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialProvider
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import java.time.Instant

@Schema(description = "계정 연동 재인증 요청(이미 연동된 provider로 소유를 재확인한다)")
data class SocialReauthRequest(
    @field:Schema(description = "재인증에 쓸 provider. 요청자에게 이미 연동돼 있어야 한다.", example = "GOOGLE")
    val provider: SocialProvider,

    @field:NotBlank
    @field:Schema(
        description = "재인증용 ID 토큰. 방금 발급받은 토큰이어야 한다(오래된 토큰은 거부 — 공용 기기 보호).",
    )
    val idToken: String,
)

@Schema(description = "재인증 결과로 발급된 일회용 링크 코드")
data class LinkCodeResponse(
    @field:Schema(
        description = "연동 요청의 X-Manyak-Link-Code 헤더에 실을 일회용 코드. 연동에 성공하면 소비된다.",
    )
    val linkCode: String,

    @field:Schema(description = "코드 만료 시각. 만료되면 재인증부터 다시 한다.")
    val expiresAt: Instant,
)

@Schema(description = "계정 연동 요청(연동할 provider는 경로에, 링크 코드는 헤더에 싣는다)")
data class AccountLinkRequest(
    @field:NotBlank
    @field:Schema(description = "연동할 provider가 발급한 OIDC ID 토큰(JWT).")
    val idToken: String,
)
