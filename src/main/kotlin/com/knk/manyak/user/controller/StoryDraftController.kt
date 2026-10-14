package com.knk.manyak.user.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.user.draft.PutStoryDraftRequest
import com.knk.manyak.user.draft.StoryDraftService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/users/me/story-drafts")
class StoryDraftController(private val service: StoryDraftService) {
    @GetMapping fun list(@CurrentUserId userId: Long?) = service.list(member(userId))
    @GetMapping("/{draftId}") fun get(@CurrentUserId userId: Long?, @PathVariable draftId: String) = service.get(member(userId), draftId)
    @PutMapping("/{draftId}") fun put(@CurrentUserId userId: Long?, @PathVariable draftId: String, @RequestBody request: PutStoryDraftRequest) = service.put(member(userId), draftId, request)
    @DeleteMapping("/{draftId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@CurrentUserId userId: Long?, @PathVariable draftId: String) = service.delete(member(userId), draftId)
    private fun member(id: Long?) = id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
