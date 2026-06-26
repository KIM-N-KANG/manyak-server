package com.knk.manyak.auth.controller

import com.knk.manyak.auth.dto.MeResponse
import com.knk.manyak.auth.dto.RefreshTokenRequest
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.repository.UserRepository
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
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authTokenService: AuthTokenService,
    private val userRepository: UserRepository,
) {

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

    /** sub(공개 식별자)를 UUID로 파싱한다. 형식이 깨졌으면(토큰은 유효했어도) 401로 본다. */
    private fun parsePublicId(subject: String?): UUID =
        try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        }
}
