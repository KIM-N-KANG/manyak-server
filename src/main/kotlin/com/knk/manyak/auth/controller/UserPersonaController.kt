package com.knk.manyak.auth.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.global.security.requireUser
import com.knk.manyak.auth.dto.*
import com.knk.manyak.auth.service.UserPersonaService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/users/me/personas")
class UserPersonaController(private val service: UserPersonaService) {
    @GetMapping fun list(@CurrentUserId userId: Long?) = service.list(requireUser(userId))
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@CurrentUserId userId: Long?, @RequestBody request: CreateUserPersonaRequest) = service.create(requireUser(userId), request)
    @PatchMapping("/{personaId}")
    fun update(@CurrentUserId userId: Long?, @PathVariable personaId: String, @RequestBody request: UpdateUserPersonaRequest) = service.update(requireUser(userId), personaId, request)
    @DeleteMapping("/{personaId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@CurrentUserId userId: Long?, @PathVariable personaId: String) = service.delete(requireUser(userId), personaId)
}
