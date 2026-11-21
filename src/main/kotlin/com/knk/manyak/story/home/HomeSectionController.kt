package com.knk.manyak.story.home

import com.knk.manyak.adminkit.security.AdminRequester
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "Admin Home Sections", description = "관리자 홈 섹션 API")
@RestController
@RequestMapping("/api/v1/admin/home-sections")
class AdminHomeSectionController(private val sections: HomeSectionService) {
    @GetMapping fun list() = sections.list()
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: HomeSectionCreateRequest, request: HttpServletRequest) = sections.create(body.raw, AdminRequester.userId(request))
    @PatchMapping("/{sectionId}")
    fun update(@PathVariable sectionId: UUID, @RequestBody body: HomeSectionUpdateRequest, request: HttpServletRequest) = sections.update(sectionId, body.raw, AdminRequester.userId(request))
    @DeleteMapping("/{sectionId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable sectionId: UUID, @RequestBody body: HomeSectionDeleteRequest, request: HttpServletRequest) = sections.delete(sectionId, body.raw, AdminRequester.userId(request))
}

@Tag(name = "Stories", description = "스토리 API")
@RestController
class StoryHomeController(private val sections: HomeSectionService) {
    @GetMapping("/api/v1/stories/home") fun home() = sections.home()
}
