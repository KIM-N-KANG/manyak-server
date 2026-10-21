package com.knk.manyak.push.controller

import com.knk.manyak.push.dto.PushDeliveryType
import com.knk.manyak.push.service.PushDeliveryService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/admin/push/delivery-stats")
class AdminPushDeliveryController(private val service: PushDeliveryService) {
    @GetMapping
    fun stats(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) type: PushDeliveryType?,
    ) = service.stats(from, to, type)
}
