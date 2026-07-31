package com.knk.manyak.auth.link

import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Auth", description = "인증 API")
@SecurityRequirement(name = "bearerAuth") // 연동은 로그인된 세션에서만 시작한다(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/auth")
class AccountLinkController(
    private val accountLinkService: AccountLinkService,
) {

    @Operation(
        summary = "계정 연동 상태 조회",
        description = "로그인 가능한 provider(GOOGLE·KAKAO)별 연동 여부를 반환합니다. 마이 페이지가 이 응답으로 " +
            "연동 가능한 provider와 재인증에 쓸 수 있는 provider를 함께 판정합니다. 인증 필수입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = AccountLinkResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/links")
    fun getLinks(
        @CurrentUserId userId: Long?,
    ): AccountLinkResponse = accountLinkService.getLinks(requireUserId(userId))

    @Operation(
        summary = "계정 연동 추가",
        description = "로그인된 계정에 다른 소셜 provider를 추가로 연동합니다. 연동하면 그 provider로 로그인해도 " +
            "같은 계정(같은 크레딧·서재)으로 들어옵니다.\n\n" +
            "**이미 연동된 provider로의 재인증이 선행돼야 합니다.** 재인증 토큰은 방금 발급받은 것이어야 하며, " +
            "오래된 토큰은 거부합니다(공용 기기에 남은 세션 악용 차단).\n\n" +
            "이 API는 새 계정도, 새 세션도 만들지 않습니다(가입 보상·토큰 발급 없음). 기존 access·refresh 토큰은 그대로 유효합니다. " +
            "이미 연동된 같은 소셜 계정을 다시 보내면 멱등하게 200입니다. 연동 해제는 제공하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "연동 성공(또는 이미 연동된 소셜 계정의 멱등 재요청). 최신 연동 상태를 반환합니다.",
                content = [Content(schema = Schema(implementation = AccountLinkResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(지원하지 않는 provider, idToken 누락 등)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "재인증 실패(code=REAUTH_FAILED) · 연동 대상 토큰 무효(code=SOCIAL_TOKEN_INVALID) · 정지 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "다른 계정에 이미 연동된 소셜 계정(code=SOCIAL_ACCOUNT_LINKED_TO_OTHER_USER) · " +
                    "이미 연동된 provider(code=PROVIDER_ALREADY_LINKED)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/links")
    fun link(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: AccountLinkRequest,
    ): AccountLinkResponse = accountLinkService.link(requireUserId(userId), request)

    /** 연동은 로그인된 세션 전용이다. 시큐리티가 이미 인증을 요구하므로 여기 도달하면서 null이면 사용자 행이 사라진 경우다. */
    private fun requireUserId(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
