package com.knk.manyak.auth.link

import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Auth", description = "인증 API")
@SecurityRequirement(name = "bearerAuth") // 연동은 로그인된 세션에서만 시작한다(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/auth/links")
class AccountLinkController(
    private val accountLinkService: AccountLinkService,
) {

    @Operation(
        summary = "계정 연동 재인증",
        description = "연동을 시작하기 전에 **이미 연동된 provider**로 소유를 재확인하고 일회용 링크 코드를 발급합니다. " +
            "연동은 계정에 로그인 수단을 영구히 추가하는 작업이라 세션만으로는 부족합니다(공용 기기에 남은 세션 악용 차단). " +
            "재인증 토큰은 방금 발급받은 것이어야 하며, 오래된 토큰은 거부합니다.\n\n" +
            "발급된 코드는 짧게 만료되며(기본 5분), 연동 요청의 `X-Manyak-Link-Code` 헤더에 실어 보냅니다. " +
            "실패 사유는 구분하지 않습니다(어떤 소셜 계정이 이 회원에게 연동돼 있는지 노출하지 않기 위해서입니다).",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "재인증 성공(링크 코드 발급)",
                content = [Content(schema = Schema(implementation = LinkCodeResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(지원하지 않는 provider, idToken 누락 등)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "재인증 실패(code=REAUTH_FAILED) · 정지 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/reauth")
    fun reauthenticate(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: SocialReauthRequest,
    ): LinkCodeResponse = accountLinkService.reauthenticate(requireUserId(userId), request)

    @Operation(
        summary = "계정 연동 추가",
        description = "로그인된 계정에 다른 소셜 provider를 추가로 연동합니다. 연동하면 그 provider로 로그인해도 " +
            "같은 계정(같은 크레딧·서재)으로 들어옵니다. 재인증으로 받은 링크 코드를 `X-Manyak-Link-Code` 헤더에 실어야 합니다 " +
            "(코드는 URL에 싣지 않습니다 — 요청 URI가 구조화 로그·Sentry에 남습니다).\n\n" +
            "이 API는 새 계정도, 새 세션도 만들지 않습니다(가입 보상·토큰 발급 없음). 기존 access·refresh 토큰은 그대로 유효하며, " +
            "연동 후 상태는 `GET /auth/me`의 `linkedProviders`로 확인합니다.\n\n" +
            "링크 코드는 **성공했을 때만** 소비됩니다. 403·409로 실패하면 코드가 남아 만료 전까지 재인증 없이 다시 시도할 수 있습니다. " +
            "이미 연동된 소셜 계정을 다시 보내면(내 계정이든 남의 계정이든) 409입니다. 연동 해제는 제공하지 않습니다.\n\n" +
            "탈퇴한 계정에 연결됐던 소셜 계정도 409(code=SOCIAL_ACCOUNT_WITHDRAWN)입니다. 그 신원으로 **로그인**은 여전히 가능하며(재가입), " +
            "막히는 것은 다른 계정에 붙이는 것뿐입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "연동 성공(본문 없음)"),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(지원하지 않는 provider, idToken 누락 등)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "링크 코드 무효·만료·소비됨·타인 소유(code=REAUTH_FAILED) · " +
                    "연동 대상 토큰 무효(code=SOCIAL_TOKEN_INVALID) · 정지 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "다른 계정에 이미 연동된 소셜 계정(code=SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER) · " +
                    "이미 연동된 provider(code=PROVIDER_ALREADY_LINKED) · " +
                    "탈퇴한 계정에 연결됐던 소셜 계정(code=SOCIAL_ACCOUNT_WITHDRAWN)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/{provider}")
    fun link(
        @CurrentUserId userId: Long?,
        @Parameter(description = "연동할 provider(소문자)", example = "kakao")
        @PathVariable provider: String,
        @Parameter(description = "재인증으로 받은 일회용 링크 코드", required = true)
        @RequestHeader(value = HEADER_LINK_CODE, required = false) linkCode: String?,
        @Valid @RequestBody request: AccountLinkRequest,
    ) = accountLinkService.link(requireUserId(userId), parseProvider(provider), linkCode, request)

    /** 경로의 provider는 소문자 표기가 정본이다. enum에 없는 값은 400으로 끊는다(지원 여부 판정은 서비스가 한다). */
    private fun parseProvider(raw: String): SocialProvider =
        SocialProvider.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 방식입니다.")

    /** 연동은 로그인된 세션 전용이다. 시큐리티가 이미 인증을 요구하므로 여기 도달하면서 null이면 사용자 행이 사라진 경우다. */
    private fun requireUserId(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")

    companion object {
        /** 링크 코드 전송 헤더. 핸드오프 코드와 같은 규칙으로 URL이 아니라 헤더로만 받는다. */
        const val HEADER_LINK_CODE = "X-Manyak-Link-Code"
    }
}
