package com.knk.manyak.credit.controller

import com.knk.manyak.credit.dto.CreditBalanceResponse
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Credits", description = "크레딧 API")
@RestController
@RequestMapping("/api/v1/users/me")
class CreditController(
    private val creditWalletService: CreditWalletService,
) {

    @Operation(
        summary = "크레딧 잔액 조회",
        description = "요청자의 현재 크레딧 잔액을 반환합니다. 지갑이 없으면 0입니다. 인증 필수입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = CreditBalanceResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/credits")
    fun getMyCredits(
        @CurrentUserId userId: Long?,
    ): CreditBalanceResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return CreditBalanceResponse(balance = creditWalletService.balanceOf(ownerId))
    }
}
