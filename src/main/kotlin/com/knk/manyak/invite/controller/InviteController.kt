package com.knk.manyak.invite.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.invite.dto.InviteResponse
import com.knk.manyak.invite.service.InviteService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Invite", description = "초대 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/users/me")
class InviteController(
    private val inviteService: InviteService,
) {

    @Operation(
        summary = "내 초대 코드·링크 조회",
        description = "요청자의 고유 초대 코드와 공유 링크를 반환합니다. 코드가 없으면 최초 조회 시 발급하며, " +
            "이후에는 같은 코드를 반환합니다. 인증 필수입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = InviteResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/invite")
    fun getMyInvite(
        @CurrentUserId userId: Long?,
    ): InviteResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return inviteService.getOrCreateInvite(ownerId)
    }
}
