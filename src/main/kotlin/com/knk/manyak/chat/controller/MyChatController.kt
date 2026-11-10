package com.knk.manyak.chat.controller

import com.knk.manyak.chat.dto.ChatSummaryResponse
import com.knk.manyak.chat.service.ChatQueryService
import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.global.security.requireUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 회원 서재의 내 채팅 목록(KNK-447). 다른 기기에서 로그인해도 같은 서재를 보도록 서버가 정본을 제공한다(US-9-4).
 *
 * 카드 스키마는 `POST /chats/batch`(MVP 로컬 조회)와 동일하다. URL이 `/users/me` 아래라 Swagger 태그는 Users를 쓴다.
 */
@Tag(name = "Users", description = "회원 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/users/me")
class MyChatController(
    private val chatQueryService: ChatQueryService,
) {

    @Operation(
        summary = "내 채팅 목록 조회",
        description = "요청자가 소유한 채팅 카드를 최근 활동순으로 반환합니다. 소프트 삭제는 제외하며, limit(기본 100, 최대 100)으로 상한을 둡니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(array = ArraySchema(schema = Schema(implementation = ChatSummaryResponse::class)))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/chats")
    fun getMyChats(
        @CurrentUserId userId: Long?,
        @Parameter(description = "최대 개수(기본 100, 최대 100)")
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<ChatSummaryResponse> =
        chatQueryService.getMyChats(requireUser(userId), limit.coerceIn(1, MAX_LIMIT))

    private companion object {
        const val MAX_LIMIT = 100
    }
}
