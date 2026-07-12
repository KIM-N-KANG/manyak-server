package com.knk.manyak.invite.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.invite.dto.InviteRedeemRequest
import com.knk.manyak.invite.dto.InviteRedeemResponse
import com.knk.manyak.invite.dto.InviteResponse
import com.knk.manyak.invite.service.InviteService
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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
        summary = "내 초대 코드 조회",
        description = "요청자의 고유 초대 코드와 이번 KST 월 초대 보상 진행(monthlyRewardCount·monthlyRewardLimit)을 " +
            "반환합니다. 코드가 없으면 최초 조회 시 발급하며, 이후에는 같은 코드를 반환합니다. 인증 필수입니다.",
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

    @Operation(
        summary = "초대 코드 입력·보상 적립",
        description = "다른 회원의 초대 코드를 제출해 초대자·제출자 양쪽에 각 500 크레딧을 적립합니다(스펙 §4-3-7, KNK-567). " +
            "제출 자격은 계정당 평생 1회이며, 코드는 trim·대문자 정규화 후 비교합니다. " +
            "초대자가 월 상한(10회)에 도달했으면 초대자 적립만 건너뛰고 제출자는 적립하며 응답은 200입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "적립 성공. amount는 제출자에게 적립된 크레딧(제출자가 월 상한이면 0), balance는 적립 후 잔액.",
                content = [Content(schema = Schema(implementation = InviteRedeemResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "빈 값 또는 형식 위반(대문자·숫자 8자가 아님)",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "403",
                description = "정지된 계정",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "일치하는 초대 코드 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "자기 코드 제출(code=INVITE_SELF_CODE) 또는 재제출(code=INVITE_ALREADY_REDEEMED) — 바디 code로 구분",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/invite/redeem")
    fun redeemInviteCode(
        @CurrentUserId userId: Long?,
        @Valid @RequestBody request: InviteRedeemRequest,
    ): InviteRedeemResponse {
        val redeemerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return inviteService.redeem(redeemerId, request.code)
    }
}
