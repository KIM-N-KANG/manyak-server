package com.knk.manyak.push.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.push.dto.PushTokenDeleteRequest
import com.knk.manyak.push.dto.PushTokenRegisterRequest
import com.knk.manyak.push.service.DevicePushTokenService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Push", description = "푸시 알림 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(/users/me/** 는 anyRequest().authenticated()).
@RestController
@RequestMapping("/api/v1/users/me/push-tokens")
class DevicePushTokenController(
    private val devicePushTokenService: DevicePushTokenService,
) {

    @Operation(
        summary = "디바이스 푸시 토큰 등록",
        description = "앱이 FCM에서 발급받은 토큰을 요청자의 기기로 등록합니다(KNK-1131). 같은 토큰 재등록은 갱신(멱등)이고, " +
            "다른 회원이 같은 토큰을 등록하면 소유자가 옮겨갑니다(한 기기에서 계정 전환). 한 회원이 기기 여러 대를 " +
            "등록할 수 있습니다. 인증 필수이며 게스트는 대상이 아닙니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "등록 또는 갱신 완료", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "400", description = "token 누락·공백·512자 초과, platform 누락·미지원 값", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "401", description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PutMapping
    fun register(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: PushTokenRegisterRequest,
    ) {
        devicePushTokenService.register(requireUser(userId), request.token, request.platform)
    }

    @Operation(
        summary = "디바이스 푸시 토큰 삭제",
        description = "로그아웃 등으로 이 기기가 더 이상 요청자의 푸시를 받지 않게 합니다. 요청자 소유 토큰만 지우며, " +
            "없거나 남의 토큰이면 아무 일도 하지 않고 204입니다(멱등). 탈퇴는 이 API 없이도 회원의 토큰을 전부 지웁니다. " +
            "지울 토큰은 경로가 아니라 **본문**으로 받습니다 — 경로에 실으면 토큰 원문이 액세스 로그·Sentry breadcrumb에 남습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 완료 또는 지울 것 없음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "400", description = "token 누락·공백·512자 초과", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "401", description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping
    fun unregister(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: PushTokenDeleteRequest,
    ) {
        devicePushTokenService.unregister(requireUser(userId), request.token)
    }

    private fun requireUser(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
