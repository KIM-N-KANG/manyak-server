package com.knk.manyak.push.controller

import com.knk.manyak.push.dto.PushDeliveryReportRequest
import com.knk.manyak.push.service.PushDeliveryService
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Hidden
@RestController
@RequestMapping("/internal/push-deliveries")
class InternalPushDeliveryController(private val service: PushDeliveryService) {
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun report(@Valid @RequestBody report: PushDeliveryReportRequest) = service.record(report)
}
