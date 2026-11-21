package com.knk.manyak.push.controller

import com.knk.manyak.push.dto.PushDeliveryType
import com.knk.manyak.push.dto.PushKind
import com.knk.manyak.push.service.PushDeliveryService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@Tag(name = "Admin Push", description = "관리자 푸시 발송 API")
@RestController
@RequestMapping("/api/v1/admin/push/delivery-stats")
class AdminPushDeliveryController(private val service: PushDeliveryService) {
    @GetMapping
    fun stats(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) type: PushDeliveryType?,
        @RequestParam(required = false) kind: PushKind?,
    ) = service.stats(from, to, type, kind)
}
