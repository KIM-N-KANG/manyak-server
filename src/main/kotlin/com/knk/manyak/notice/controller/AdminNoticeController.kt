package com.knk.manyak.notice.controller

import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.auth.consent.SocialAuthPrivacyFilter
import com.knk.manyak.notice.dto.*
import com.knk.manyak.notice.service.NoticeService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/notices")
class AdminNoticeController(private val service: NoticeService) {
    @GetMapping
    fun list(@Valid @ModelAttribute query: AdminNoticesRequest) = service.list(query)
    @GetMapping("/{noticeId}")
    fun detail(@PathVariable noticeId: UUID) = service.detail(noticeId)
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody body: CreateNoticeRequest, request: HttpServletRequest) = SocialAuthPrivacyFilter.safely {
        service.create(AdminRequester.userId(request), body)
    }
    @PatchMapping("/{noticeId}")
    fun update(@PathVariable noticeId: UUID, @Valid @RequestBody body: UpdateNoticeRequest, request: HttpServletRequest) = SocialAuthPrivacyFilter.safely {
        service.update(AdminRequester.userId(request), noticeId, body)
    }
    @PostMapping("/{noticeId}/publish")
    fun publish(@PathVariable noticeId: UUID, @Valid @RequestBody body: PublishNoticeRequest, request: HttpServletRequest) = SocialAuthPrivacyFilter.safely {
        service.publish(AdminRequester.userId(request), noticeId, body)
    }
    @PostMapping("/{noticeId}/unpublish")
    fun unpublish(@PathVariable noticeId: UUID, @Valid @RequestBody body: NoticeReasonRequest, request: HttpServletRequest) = SocialAuthPrivacyFilter.safely {
        service.unpublish(AdminRequester.userId(request), noticeId, body.reason)
    }
    @DeleteMapping("/{noticeId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable noticeId: UUID, @Valid @RequestBody body: NoticeReasonRequest, request: HttpServletRequest) = SocialAuthPrivacyFilter.safely {
        service.delete(AdminRequester.userId(request), noticeId, body.reason)
    }
}
