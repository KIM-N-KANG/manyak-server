package com.knk.manyak.user.consent

import com.knk.manyak.global.observability.RequestCorrelationFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Guest", description = "게스트 API")
@RestController
@RequestMapping("/api/v1/guests/consents")
class GuestConsentController(private val service: GuestConsentService) {
    @Operation(
        summary = "게스트 개인정보 수집 동의 조회",
        description = "인증 없이 디바이스별 현행 문서의 동의 필요 여부를 반환합니다. " +
            "미동의 게스트의 다른 API를 차단하지 않으며 회원 동의로 이관하지 않습니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "조회 성공", content = [Content(schema = Schema(implementation = GuestConsentResponse::class))]),
        ApiResponse(responseCode = "400", description = "디바이스 헤더 누락 또는 공백", content = [Content(schema = Schema(hidden = true))]),
    ])
    @GetMapping
    fun getConsents(
        @Parameter(description = "체험 한도와 같은 디바이스 ID", required = true)
        @RequestHeader(name = RequestCorrelationFilter.HEADER_DEVICE_ID, required = false) deviceId: String?,
    ): GuestConsentResponse = service.getConsents(deviceId)

    @Operation(
        summary = "게스트 개인정보 수집 동의 기록",
        description = "guestPrivacy의 현행 버전만 기록하며 같은 버전 재제출은 최초 동의 시각을 유지합니다. " +
            "CONSENT_VERSION_MISMATCH이면 문서를 다시 표시해 동의받아야 하며 버전만 바꿔 자동 재전송하지 않습니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "기록 후 상태", content = [Content(schema = Schema(implementation = GuestConsentResponse::class))]),
        ApiResponse(responseCode = "400", description = "디바이스 헤더 누락 또는 공백, 동의 미제출, 형식 오류, 버전 불일치(code: CONSENT_VERSION_MISMATCH)", content = [Content(schema = Schema(hidden = true))]),
    ])
    @PostMapping
    fun recordConsents(
        @Parameter(description = "체험 한도와 같은 디바이스 ID", required = true)
        @RequestHeader(name = RequestCorrelationFilter.HEADER_DEVICE_ID, required = false) deviceId: String?,
        @RequestBody request: GuestConsentRequest,
    ): GuestConsentResponse = service.recordConsents(deviceId, request)
}
