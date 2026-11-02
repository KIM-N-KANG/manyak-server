package com.knk.manyak.chat.controller

import com.knk.manyak.chat.dto.CreateChatResponse
import com.knk.manyak.chat.dto.ForkChatRequest
import com.knk.manyak.chat.service.ChatForkService
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/chats")
@Tag(name = "Chats")
class ChatForkController(private val forks: ChatForkService) {
    @Operation(summary = "채팅 포크", description = "회원 소유 채팅을 대상 턴의 AI 응답까지 무료로 복사합니다. 원본은 바꾸지 않습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "채팅 생성 응답, suggestedInputs는 빈 배열")
    @ApiResponse(responseCode = "400", description = "turnId 누락 또는 양수가 아님")
    @ApiResponse(responseCode = "401", description = "인증 필요")
    @ApiResponse(responseCode = "403", description = "정지 계정 또는 채팅 소유권 위반")
    @ApiResponse(responseCode = "404", description = "채팅 또는 숨기지 않은 완료 턴을 찾을 수 없음")
    @PostMapping("/{chatId}/fork")
    @ResponseStatus(HttpStatus.CREATED)
    fun fork(@PathVariable chatId: String, @Valid @RequestBody request: ForkChatRequest,
             @CurrentUserId userId: Long?): CreateChatResponse =
        forks.fork(chatId, request.turnId, userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED))
}
