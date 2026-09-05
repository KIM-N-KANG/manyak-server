package com.knk.manyak.user.controller

import com.knk.manyak.auth.dto.MeResponse
import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.user.dto.ProfilePresetResponse
import com.knk.manyak.user.dto.UpdateProfileRequest
import com.knk.manyak.user.service.UserProfileService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 프로필 수정과 프리셋 목록(KNK-1147, 정책 KNK-1146).
 *
 * 두 경로 모두 인증 필수다 — `/users/me` 하위는 SecurityConfig의 `anyRequest().authenticated()`가 이미 막고,
 * `/profile-presets`도 같은 규칙에 걸린다(공개 목록이 아니라 로그인 후 설정 화면의 선택지다).
 */
@Tag(name = "Users", description = "회원 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1")
class UserProfileController(
    private val userProfileService: UserProfileService,
) {

    @Operation(
        summary = "프로필 수정",
        description = "닉네임과 프로필 이미지를 바꿉니다(KNK-1147). **보낸 필드만 반영**하며 둘 다 없으면 400입니다. " +
            "닉네임은 앞뒤 공백을 지운 뒤 2~20자이고 한글·영문·숫자·공백만 쓸 수 있습니다(연속 공백·자모 단독·" +
            "특수문자·이모지는 400). 유일성은 대소문자와 공백을 무시한 정규화 기준이라 `Story Teller`와 " +
            "`storyteller`는 같은 닉네임으로 보고 409입니다 — 다만 자기 닉네임의 대소문자·공백만 바꾸는 것은 " +
            "허용합니다. 프로필 이미지는 프리셋 선택만 지원하며(업로드 없음) 닉네임 변경과 독립입니다. " +
            "응답은 `GET /auth/me`와 같은 스키마입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "수정 후 상태",
                content = [Content(schema = Schema(implementation = MeResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "바꿀 항목 없음, 닉네임 규칙 위반, 알 수 없는 프리셋 키",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음·탈퇴 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(responseCode = "403", description = "정지된 계정", content = [Content(schema = Schema(hidden = true))]),
            ApiResponse(
                responseCode = "409",
                description = "이미 사용 중인 닉네임(code: NICKNAME_TAKEN)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PatchMapping("/users/me")
    fun updateProfile(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): MeResponse = userProfileService.updateProfile(requireUser(userId), request)

    @Operation(
        summary = "프로필 이미지 프리셋 목록",
        description = "고를 수 있는 프로필 이미지 전부를 돌려줍니다(KNK-1147). `key`를 프로필 수정 요청의 " +
            "`profileImagePreset`에 그대로 넣습니다. 순서는 고정이라 요청마다 흔들리지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = ProfilePresetResponse::class)))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조)",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/profile-presets")
    fun listPresets(): List<ProfilePresetResponse> = userProfileService.listPresets()

    // /users/me/** 는 anyRequest().authenticated()로 보호되지만, 토큰은 유효하나 사용자가 사라진 경우 null이 올 수 있어 401로 통일한다.
    private fun requireUser(userId: Long?): Long =
        userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
