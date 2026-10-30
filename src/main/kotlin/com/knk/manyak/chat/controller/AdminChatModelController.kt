package com.knk.manyak.chat.controller

import com.knk.manyak.admin.security.AdminRequester
import com.knk.manyak.chat.dto.AdminChatModelPromotionRequest
import com.knk.manyak.chat.dto.AdminChatModelPromotionDeleteRequest
import com.knk.manyak.chat.service.AdminChatModelService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/chat-models")
class AdminChatModelController(private val models: AdminChatModelService) {
    @GetMapping
    fun list() = models.list()

    @PutMapping("/{code}/promotion")
    fun upsert(@PathVariable code: String, @Valid @RequestBody body: AdminChatModelPromotionRequest, request: HttpServletRequest) =
        models.upsert(AdminRequester.userId(request), code, body)

    @DeleteMapping("/{code}/promotion")
    fun delete(@PathVariable code: String, @Valid @RequestBody body: AdminChatModelPromotionDeleteRequest, request: HttpServletRequest) =
        models.delete(AdminRequester.userId(request), code, body.reason)
}
