package com.knk.manyak.migration.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.migration.dto.MigrationRequest
import com.knk.manyak.migration.dto.MigrationResponse
import com.knk.manyak.migration.service.GuestDataMigrationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Auth", description = "인증 API")
@SecurityRequirement(name = "bearerAuth") // 게스트 데이터 마이그레이션은 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/auth")
class MigrationController(
    private val guestDataMigrationService: GuestDataMigrationService,
) {

    @Operation(
        summary = "게스트 데이터 마이그레이션",
        description = "로그인 직후, 기기(localStorage)에 쌓인 게스트 스토리·채팅의 공개 ID 목록을 제출받아 " +
            "요청자 계정으로 소유권을 이관(클레임)합니다. user_id가 NULL인 행에만 설정하며, 항목별 결과를 status로 반환합니다. " +
            "효과는 멱등하고, 일부 항목이 충돌해도 전체를 롤백하지 않습니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "이관 처리됨(항목별 status 반환)",
                content = [Content(schema = Schema(implementation = MigrationResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "요청 값이 올바르지 않음(공개 ID UUID 형식 오류·배열 100개 초과)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/migrate")
    fun migrate(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: MigrationRequest,
    ): MigrationResponse {
        // /auth/migrate는 인증 필수 경로(anyRequest().authenticated())라 미인증은 필터에서 401이 된다.
        // userId가 null인 경우는 토큰은 유효하나 그 사용자가 사라진 상태이므로 401로 처리한다(/me와 동일).
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return guestDataMigrationService.migrate(ownerId, request)
    }
}
