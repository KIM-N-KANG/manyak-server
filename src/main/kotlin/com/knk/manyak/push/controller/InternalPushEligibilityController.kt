package com.knk.manyak.push.controller

import com.knk.manyak.push.dto.PushEligibilityResponse
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.service.PushEligibilityService
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Hidden // 공개 OpenAPI 문서로 내부 경로를 노출하지 않는다.
@RestController
@RequestMapping("/internal/users/{publicId}/push-eligibility")
class InternalPushEligibilityController(private val service: PushEligibilityService) {
    @GetMapping
    fun getEligibility(
        @PathVariable publicId: UUID,
        @RequestParam(required = false) kind: PushKind?,
        @RequestParam(required = false) at: Instant?,
    ): PushEligibilityResponse = service.getEligibility(
        publicId,
        kind ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "kind는 필수입니다."),
        at ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "at은 필수입니다."),
    )
}
