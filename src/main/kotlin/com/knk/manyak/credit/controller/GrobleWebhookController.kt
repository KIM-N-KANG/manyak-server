package com.knk.manyak.credit.controller

import com.knk.manyak.credit.service.GrobleWebhookService
import com.knk.manyak.global.error.ApiErrorResponse
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class GrobleWebhookController(private val webhook: GrobleWebhookService) {
    @Operation(summary = "그로블 결제 웹훅 수신", description = "raw body HMAC 검증 후 결제 적립·환불 회수를 처리합니다. 타임스탬프·서명 오류 401, JSON 오류 400, 시크릿 미설정 503.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "처리 또는 무시 완료"),
        ApiResponse(responseCode = "400", description = "JSON 오류", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "타임스탬프 또는 서명 오류", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
        ApiResponse(responseCode = "503", description = "시크릿 미설정", content = [Content(mediaType = "application/json", schema = Schema(implementation = ApiErrorResponse::class))]),
    ])
    @SecurityRequirements
    @PostMapping("/api/v1/webhooks/groble")
    fun receive(
        @RequestBody body: ByteArray,
        @RequestHeader("X-Groble-Timestamp", required = false) timestamp: String?,
        @RequestHeader("X-Groble-Signature", required = false) signature: String?,
        @RequestHeader("X-Groble-Signature-Previous", required = false) previous: String?,
        @RequestHeader("X-Groble-Idempotency-Key", required = false) deliveryKey: String?,
    ) = webhook.receive(body, timestamp, signature, previous, deliveryKey)
}
