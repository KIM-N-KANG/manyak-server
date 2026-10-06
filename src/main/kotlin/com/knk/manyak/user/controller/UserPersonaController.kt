package com.knk.manyak.user.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.user.dto.*
import com.knk.manyak.user.service.UserPersonaService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/users/me/personas")
class UserPersonaController(private val service: UserPersonaService) {
    @GetMapping fun list(@CurrentUserId userId: Long?) = service.list(member(userId))
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@CurrentUserId userId: Long?, @RequestBody request: CreateUserPersonaRequest) = service.create(member(userId), request)
    @PatchMapping("/{personaId}")
    fun update(@CurrentUserId userId: Long?, @PathVariable personaId: String, @RequestBody request: UpdateUserPersonaRequest) = service.update(member(userId), personaId, request)
    @DeleteMapping("/{personaId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@CurrentUserId userId: Long?, @PathVariable personaId: String) = service.delete(member(userId), personaId)
    private fun member(id: Long?) = id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
