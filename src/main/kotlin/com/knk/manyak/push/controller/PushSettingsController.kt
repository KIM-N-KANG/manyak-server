package com.knk.manyak.push.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.push.dto.PushSettingsResponse
import com.knk.manyak.push.dto.PushSettingsUpdateRequest
import com.knk.manyak.push.service.PushSettingsService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Push", description = "푸시 알림 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(/users/me/** 는 anyRequest().authenticated()).
@RestController
@RequestMapping("/api/v1/users/me/push-settings")
class PushSettingsController(
    private val pushSettingsService: PushSettingsService,
) {

    @Operation(
        summary = "알림 수신 동의 조회",
        description = "요청 회원의 알림 수신 동의 상태를 조회합니다(KNK-1132). 서비스 알림은 기본 켜짐(옵트아웃), " +
            "광고 알림과 야간 광고 알림은 기본 꺼짐(옵트인)입니다. 저장 정본은 광고성 두 값의 동의 시각이지만 " +
            "응답에는 토글 상태(boolean)만 싣습니다. 정지 계정은 설정 화면 자체를 쓸 수 없어 조회도 403입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = PushSettingsResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @GetMapping
    fun getPushSettings(
        @CurrentUserId userId: Long?,
    ): PushSettingsResponse = pushSettingsService.getSettings(requireUser(userId))

    @Operation(
        summary = "알림 수신 동의 변경",
        description = "알림 수신 동의를 전체 교체합니다(KNK-1132). 세 필드가 **모두 필수**이며 누락은 400입니다 — " +
            "보내지 않은 설정이 조용히 꺼지지 않게 하기 위해서입니다. 광고 알림을 켜면 동의 시각을 기록하고, " +
            "이미 동의한 상태면 최초 동의 시각을 유지합니다(증빙은 최초 시점). 광고 알림을 끄면 야간 동의도 함께 " +
            "지웁니다. 야간 광고만 켜는 요청(`marketingPush=false`, `marketingNightPush=true`)은 400입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "변경 후 상태",
                content = [Content(schema = Schema(implementation = PushSettingsResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "필드 누락·형식 오류, 또는 광고 동의 없이 야간만 켜는 요청(code: NIGHT_PUSH_REQUIRES_MARKETING)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
        ],
    )
    @PutMapping
    fun updatePushSettings(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: PushSettingsUpdateRequest,
    ): PushSettingsResponse = pushSettingsService.updateSettings(requireUser(userId), request)

    // /users/me/** 는 anyRequest().authenticated()로 보호되지만, 토큰은 유효하나 사용자가 사라진 경우 null이 올 수 있어 401로 통일한다.
    private fun requireUser(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
