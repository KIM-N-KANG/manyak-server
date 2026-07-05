package com.knk.manyak.auth.controller

import com.knk.manyak.auth.dto.LogoutRequest
import com.knk.manyak.auth.dto.GoogleLoginRequest
import com.knk.manyak.auth.dto.MeResponse
import com.knk.manyak.auth.dto.RefreshTokenRequest
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.GoogleLoginService
import com.knk.manyak.auth.token.AuthTokenService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authTokenService: AuthTokenService,
    private val userRepository: UserRepository,
    private val googleLoginService: GoogleLoginService,
) {

    @Operation(
        summary = "Google 로그인",
        description = "Google ID 토큰을 검증해 사용자를 find-or-create하고 access+refresh 토큰을 발급합니다. " +
            "선택 필드 inviteCode를 최초 가입과 함께 보내면 초대자·피초대자 양쪽에 크레딧을 적립합니다" +
            "(미해결·자기 코드·이미 가입된 계정의 제출은 무시). " +
            "토큰이 유효하지 않으면(서명·만료·issuer·audience 불일치) 401, 본문이 올바르지 않으면 400으로 응답합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "로그인 성공(토큰 발급)",
                content = [Content(schema = Schema(implementation = TokenResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(idToken 누락 등)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "유효하지 않은 Google ID 토큰",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/login/google")
    fun loginWithGoogle(
        @Valid @RequestBody request: GoogleLoginRequest,
    ): TokenResponse = googleLoginService.login(request.idToken, request.inviteCode)

    @Operation(
        summary = "현재 로그인 사용자 조회",
        description = "Authorization: Bearer <accessToken> 의 sub(공개 식별자)로 사용자를 조회합니다. " +
            "토큰이 없거나 만료·위조됐으면 401, 토큰의 사용자가 더 이상 존재하지 않으면 401로 응답합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = MeResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal jwt: Jwt,
    ): MeResponse {
        val publicId = parsePublicId(jwt.subject)
        val user = userRepository.findByPublicId(publicId)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return MeResponse(
            id = user.publicId.toString(),
            nickname = user.nickname,
            profileImageUrl = user.profileImageUrl,
            status = user.status,
        )
    }

    @Operation(
        summary = "refresh 토큰 회전",
        description = "유효한 refresh 토큰으로 새 access+refresh를 발급합니다. " +
            "기존 refresh는 폐기(1회용)되며, 무효·만료·이미 회전된 토큰은 401로 응답합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "재발급 성공",
                content = [Content(schema = Schema(implementation = TokenResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "유효하지 않은 refresh 토큰",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/token/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshTokenRequest,
    ): TokenResponse = authTokenService.rotate(request.refreshToken)

    @Operation(
        summary = "로그아웃",
        description = "제시된 refresh 토큰을 폐기해 재발급(회전)을 막습니다(단일 기기 로그아웃). " +
            "멱등하므로 이미 폐기됐거나 발급된 적 없는 토큰도 204로 응답합니다. " +
            "access 토큰 없이 호출할 수 있으며, 자동 첨부된 만료·위조 access 헤더로는 막히지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "204",
                description = "로그아웃 처리됨(refresh 폐기). 본문 없음.",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
    ) {
        authTokenService.logout(request.refreshToken)
    }

    /** sub(공개 식별자)를 UUID로 파싱한다. 형식이 깨졌으면(토큰은 유효했어도) 401로 본다. */
    private fun parsePublicId(subject: String?): UUID =
        try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
}
