package com.knk.manyak.auth.controller

import com.knk.manyak.auth.dto.GoogleLoginRequest
import com.knk.manyak.auth.dto.LoginResponse
import com.knk.manyak.auth.service.AuthService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {

    @Operation(
        summary = "Google 로그인",
        description = "Google OAuth 인증 결과로 받은 ID 토큰을 검증하고 서비스 토큰을 발급합니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "로그인 성공"),
            ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음"),
            ApiResponse(responseCode = "401", description = "Google ID 토큰이 유효하지 않음"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/google")
    fun loginWithGoogle(
        @Valid @RequestBody request: GoogleLoginRequest,
    ): LoginResponse = authService.loginWithGoogle(request)
}
