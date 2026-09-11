package com.knk.manyak.credit.controller

import com.knk.manyak.credit.dto.CreateCreditOrderRequest
import com.knk.manyak.credit.dto.CreateCreditOrderResponse
import com.knk.manyak.credit.dto.CreditOrderResponse
import com.knk.manyak.credit.dto.CreditProductsResponse
import com.knk.manyak.credit.service.CreditOrderService
import com.knk.manyak.global.error.ApiErrorResponse
import com.knk.manyak.global.security.CurrentUserId
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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Tag(name = "Credits", description = "이프 API")
@RestController
class CreditOrderController(private val service: CreditOrderService) {
    @Operation(summary = "이프 충전 상품 목록", description = "인증 없이 설정 순서대로 상품 6종의 총량과 웹·앱 가격을 조회합니다.")
    @GetMapping("/api/v1/credits/products")
    fun products(): CreditProductsResponse = service.products()

    @Operation(summary = "웹 이프 충전 주문 생성", description = "PENDING 주문과 그로블 결제창 URL을 반환합니다. 주문 생성만으로 이프를 적립하지 않습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "주문 생성"),
        ApiResponse(responseCode = "400", description = "미지원 상품 또는 잘못된 요청", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "인증 필요", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "403", description = "정지 계정", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "503", description = "상품 링크 또는 웹훅 시크릿 미설정", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ])
    @PostMapping("/api/v1/users/me/credits/orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@CurrentUserId userId: Long?, @Valid @RequestBody request: CreateCreditOrderRequest): CreateCreditOrderResponse =
        service.create(ownerId(userId), request.productId)

    @Operation(summary = "본인 이프 충전 주문 조회", description = "결제 복귀 후 완료 여부 확인용입니다. 없는 주문과 타인 주문은 모두 404입니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "본인 주문 조회"),
        ApiResponse(responseCode = "401", description = "인증 필요", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "주문 없음 또는 타인 주문", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ])
    @GetMapping("/api/v1/users/me/credits/orders/{orderId}")
    fun get(@CurrentUserId userId: Long?, @PathVariable orderId: UUID): CreditOrderResponse =
        service.get(ownerId(userId), orderId)

    private fun ownerId(userId: Long?): Long = userId
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
}
