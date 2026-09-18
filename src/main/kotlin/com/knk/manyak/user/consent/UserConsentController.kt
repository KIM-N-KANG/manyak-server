package com.knk.manyak.user.consent

import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "User", description = "회원 API")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/users/me/consents")
class UserConsentController(private val userConsentService: UserConsentService) {
    @Operation(
        summary = "약관·개인정보 처리방침 동의 조회",
        description = "현행 문서 버전별 동의 필요 여부를 반환합니다. 만 14세 이상 확인 버전은 1입니다. " +
            "미동의 회원의 다른 API를 차단하지 않으며 정지 계정은 조회도 403입니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "조회 성공", content = [Content(schema = Schema(implementation = UserConsentResponse::class))]),
        ApiResponse(responseCode = "401", description = "미인증·사용자 없음·탈퇴 계정", content = [Content(schema = Schema(hidden = true))]),
        ApiResponse(responseCode = "403", description = "정지 계정", content = [Content(schema = Schema(hidden = true))]),
    ])
    @GetMapping
    fun getConsents(@CurrentUserId userId: Long?): UserConsentResponse =
        userConsentService.getConsents(requireUser(userId))

    @Operation(
        summary = "약관·개인정보 처리방침 동의 기록",
        description = "명시적으로 수락한 현행 버전만 기록합니다. 최소 한 항목이 필요하며 누락·null은 미제출입니다. " +
            "전부 검증한 뒤 저장하고 같은 버전의 재제출은 최초 동의 시각을 유지합니다. " +
            "CONSENT_VERSION_MISMATCH이면 문서를 다시 표시해 동의받아야 하며 버전만 바꿔 자동 재전송하지 않습니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "기록 후 상태", content = [Content(schema = Schema(implementation = UserConsentResponse::class))]),
        ApiResponse(responseCode = "400", description = "전부 미제출·형식 오류 또는 버전 불일치(code: CONSENT_VERSION_MISMATCH)", content = [Content(schema = Schema(hidden = true))]),
        ApiResponse(responseCode = "401", description = "미인증·사용자 없음·탈퇴 계정", content = [Content(schema = Schema(hidden = true))]),
        ApiResponse(responseCode = "403", description = "정지 계정", content = [Content(schema = Schema(hidden = true))]),
    ])
    @PostMapping
    fun recordConsents(@CurrentUserId userId: Long?, @RequestBody request: UserConsentRequest): UserConsentResponse =
        userConsentService.recordConsents(requireUser(userId), request)

    private fun requireUser(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
