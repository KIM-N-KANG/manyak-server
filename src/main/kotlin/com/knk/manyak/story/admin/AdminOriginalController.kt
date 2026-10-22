package com.knk.manyak.story.admin

import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.story.dto.ImagePresignRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/originals")
class AdminOriginalController(private val originals: AdminOriginalService) {
    @GetMapping
    fun list(@RequestParam query: Map<String, String>) = originals.list(query)

    @GetMapping("/{storyId}")
    fun detail(@PathVariable storyId: UUID) = originals.detail(storyId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: ObjectNode, request: HttpServletRequest) = originals.create(body, AdminRequester.userId(request))

    @PatchMapping("/{storyId}")
    fun update(@PathVariable storyId: UUID, @RequestBody body: ObjectNode, request: HttpServletRequest) =
        originals.update(storyId, body, AdminRequester.userId(request))

    @DeleteMapping("/{storyId}/thumbnail")
    fun deleteThumbnail(@PathVariable storyId: UUID, @RequestBody body: OriginalReason, request: HttpServletRequest) =
        originals.deleteThumbnail(storyId, body.reason, AdminRequester.userId(request))

    @PostMapping("/images/presign")
    @ResponseStatus(HttpStatus.CREATED)
    fun presignDraft(@Valid @RequestBody body: ImagePresignRequest) = originals.presign(null, body)

    @PostMapping("/{storyId}/images/presign")
    @ResponseStatus(HttpStatus.CREATED)
    fun presign(@PathVariable storyId: UUID, @Valid @RequestBody body: ImagePresignRequest) = originals.presign(storyId, body)
}

data class OriginalReason(val reason: String)
