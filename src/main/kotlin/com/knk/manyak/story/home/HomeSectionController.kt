package com.knk.manyak.story.home

import com.knk.manyak.admin.security.AdminRequester
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/home-sections")
class AdminHomeSectionController(private val sections: HomeSectionService) {
    @GetMapping fun list() = sections.list()
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: ObjectNode, request: HttpServletRequest) = sections.create(body, AdminRequester.userId(request))
    @PatchMapping("/{sectionId}")
    fun update(@PathVariable sectionId: UUID, @RequestBody body: ObjectNode, request: HttpServletRequest) = sections.update(sectionId, body, AdminRequester.userId(request))
    @DeleteMapping("/{sectionId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable sectionId: UUID, @RequestBody body: ObjectNode, request: HttpServletRequest) = sections.delete(sectionId, body, AdminRequester.userId(request))
}

@RestController
class StoryHomeController(private val sections: HomeSectionService) {
    @GetMapping("/api/v1/stories/home") fun home() = sections.home()
}
