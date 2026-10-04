package com.knk.manyak.auth.consent

import com.knk.manyak.auth.dto.SocialLoginRequest
import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.global.error.ApiErrorResponse
import com.knk.manyak.global.observability.RequestCorrelationFilter
import com.knk.manyak.user.consent.UserConsentRequest
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth/social")
class SocialConsentController(private val service: SocialConsentService) {
    @Operation(summary = "소셜 인증과 필수 동의 확인", description = "Google 또는 Kakao ID 토큰을 검증합니다. 동의가 필요하면 계정과 정식 토큰을 만들지 않고 대기 코드를 발급합니다.",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(mediaType = "application/json",
            schema = Schema(implementation = SocialLoginRequest::class),
            examples = [ExampleObject(value = """{"idToken":"<provider-id-token>","handoffCode":"<optional-handoff-code>"}""")],
        )]))
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "로그인 완료 또는 필수 동의 대기", content = [Content(mediaType = "application/json",
            schema = Schema(implementation = SocialAuthResponse::class), examples = [
                ExampleObject(name = "동의 대기", value = """{"status":"CONSENT_REQUIRED","consentToken":"<opaque-code>","expiresAt":"2026-10-05T00:10:00Z","isNewUser":true,"consents":{"terms":{"requiredVersion":"v1.4","needsConsent":true},"privacy":{"requiredVersion":"v1.7","needsConsent":true},"age14":{"requiredVersion":"1","needsConsent":true}}}"""),
                ExampleObject(name = "로그인 완료", value = """{"status":"COMPLETED","token":{"accessToken":"<access>","refreshToken":"<refresh>","expiresIn":1800,"tokenType":"Bearer","isNewUser":false}}"""),
            ],
        )]),
        ApiResponse(responseCode = "400", description = "요청 또는 provider 오류", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "유효하지 않은 ID 토큰", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ])
    @PostMapping("/{provider}")
    fun start(
        @Parameter(schema = Schema(allowableValues = ["google", "kakao"])) @PathVariable provider: String,
        @Valid @RequestBody request: SocialLoginRequest,
        @RequestHeader(value = RequestCorrelationFilter.HEADER_DEVICE_ID, required = false) deviceId: String?,
    ): SocialAuthResponse = service.start(providerOf(provider), request.idToken, deviceId, request.handoffCode)

    @Operation(summary = "필수 동의와 소셜 가입 완료", description = "현행 필수 항목을 모두 제출합니다. 계정과 동의를 함께 저장한 뒤 토큰을 발급하며 성공 시에만 대기 코드를 소비합니다. 정지 회원도 이 경로로 로그인할 수 있습니다.",
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(mediaType = "application/json",
            schema = Schema(implementation = UserConsentRequest::class),
            examples = [ExampleObject(value = """{"terms":"v1.4","privacy":"v1.7","age14":"1"}""")],
        )]))
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "동의와 로그인 완료", content = [Content(mediaType = "application/json", schema = Schema(implementation = TokenResponse::class),
            examples = [ExampleObject(value = """{"accessToken":"<access>","refreshToken":"<refresh>","expiresIn":1800,"tokenType":"Bearer","isNewUser":true}""")])]),
        ApiResponse(responseCode = "400", description = "CONSENT_VERSION_MISMATCH 또는 CONSENT_REQUIRED_MISSING. 코드는 미소비", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "CONSENT_TOKEN_INVALID. 코드 없음, 만료, 소비됨. 소셜 인증부터 재시작", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ])
    @PostMapping("/complete")
    fun complete(
        @Parameter(required = true, description = "완료 API 전용 코드. URL과 본문으로 보내지 않음")
        @RequestHeader(value = "X-Manyak-Consent-Token", required = false) code: String?,
        @RequestBody request: UserConsentRequest,
        @RequestHeader(value = RequestCorrelationFilter.HEADER_DEVICE_ID, required = false) deviceId: String?,
    ): TokenResponse = service.complete(code, request, deviceId)

    private fun providerOf(provider: String): SocialProvider = when (provider) {
        "google" -> SocialProvider.GOOGLE
        "kakao" -> SocialProvider.KAKAO
        else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 방식입니다.")
    }
}
