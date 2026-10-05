package com.knk.manyak.push.controller

import com.knk.manyak.push.service.DevicePushTokenService
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Hidden
@RestController
@RequestMapping("/internal/push-tokens")
class InternalPushTokenController(private val service: DevicePushTokenService) {
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@Valid @RequestBody request: InternalPushTokenDeleteRequest) {
        service.deleteInvalidToken(request.token)
    }
}

data class InternalPushTokenDeleteRequest(
    @field:NotBlank
    @field:Size(max = 512)
    val token: String,
)
