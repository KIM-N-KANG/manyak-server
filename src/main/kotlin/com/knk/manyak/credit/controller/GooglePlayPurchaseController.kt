package com.knk.manyak.credit.controller

import com.knk.manyak.credit.dto.GooglePlayPurchaseRequest
import com.knk.manyak.credit.dto.GooglePlayPurchaseResponse
import com.knk.manyak.credit.service.GooglePlayPurchaseService
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Credits", description = "이프 API")
@RestController
class GooglePlayPurchaseController(private val service: GooglePlayPurchaseService) {
    @Operation(summary = "Google Play 이프 구매 검증", description = "구매 토큰을 검증해 이프를 적립합니다. 같은 토큰은 본인에게만 멱등 응답하며 consume은 앱에서 수행합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "구매 적립 또는 본인 재요청"),
        ApiResponse(responseCode = "400", description = "구매 검증 실패·상품 불일치·토큰 재사용"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "정지 계정"),
        ApiResponse(responseCode = "502", description = "Google Play 연동 실패"),
        ApiResponse(responseCode = "503", description = "Google Play 결제 미설정"),
    ])
    @PostMapping("/api/v1/users/me/credits/purchases/google")
    fun purchase(@CurrentUserId userId: Long?, @Valid @RequestBody request: GooglePlayPurchaseRequest): GooglePlayPurchaseResponse =
        service.purchase(userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다."),
            request.productId, request.purchaseToken)
}
