package com.knk.manyak.story.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.story.dto.CreateGeneralStoryRequest
import com.knk.manyak.story.submission.StorySubmissionService
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/stories/submissions")
class StorySubmissionController(private val service: StorySubmissionService) {
    @GetMapping fun list(@CurrentUserId userId: Long?, @RequestParam(defaultValue = "100") limit: Int) = service.list(member(userId), limit)
    @GetMapping("/{id}") fun get(@PathVariable id: String, @CurrentUserId userId: Long?) = service.get(id, member(userId))
    @PutMapping("/{id}") @ResponseStatus(HttpStatus.ACCEPTED)
    fun resubmit(@PathVariable id: String, @CurrentUserId userId: Long?, @RequestBody request: CreateGeneralStoryRequest) = service.resubmit(id, request, member(userId))
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String, @CurrentUserId userId: Long?) = service.delete(id, member(userId))
    private fun member(id: Long?): Long = id ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
