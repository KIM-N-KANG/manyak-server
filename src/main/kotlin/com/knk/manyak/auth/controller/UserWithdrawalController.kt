package com.knk.manyak.auth.controller

import com.knk.manyak.global.security.CurrentUserId
import com.knk.manyak.global.security.requireUser
import com.knk.manyak.auth.service.UserWithdrawalService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 회원 탈퇴 API(KNK-1019). 같은 `/users/me` 아래의 내 스토리·내 채팅 목록은 story의 `MyStoryController`, chat의 `MyChatController`에 있다.
 */
@Tag(name = "Users", description = "회원 API")
@SecurityRequirement(name = "bearerAuth") // 인증 필수(스킴은 OpenApiConfig.SECURITY_SCHEME_NAME).
@RestController
@RequestMapping("/api/v1/users/me")
class UserWithdrawalController(
    private val userWithdrawalService: UserWithdrawalService,
) {

    @Operation(
        summary = "회원 탈퇴",
        description = "계정을 soft delete(DELETED)로 전환하고 닉네임 익명화·프로필 이미지 제거·소셜 연결 삭제·refresh 전체 폐기를 수행합니다. " +
            "소유 스토리는 공개 상태가 유지되며 작성자는 익명화된 닉네임으로 표시됩니다. " +
            "탈퇴 즉시 잔여 access 토큰은 전면 무효화되므로 재탈퇴를 포함한 이후 요청은 401입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "204",
                description = "탈퇴 완료",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping
    fun withdraw(@CurrentUserId userId: Long?) {
        userWithdrawalService.withdraw(requireUser(userId))
    }
}
