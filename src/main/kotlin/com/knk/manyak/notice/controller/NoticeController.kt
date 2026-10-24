package com.knk.manyak.notice.controller

import com.knk.manyak.notice.service.NoticeService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/notices")
class NoticeController(private val service: NoticeService) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "20") limit: Int, @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) tag: com.knk.manyak.notice.entity.NoticeTag?) = service.published(limit, cursor, tag)
    @GetMapping("/{noticeId}")
    fun detail(@PathVariable noticeId: String) = service.publishedDetail(noticeId)
}
