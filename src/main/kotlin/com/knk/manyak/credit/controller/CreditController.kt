package com.knk.manyak.credit.controller

import com.knk.manyak.credit.dto.CreditAttendanceResponse
import com.knk.manyak.credit.dto.CreditBalanceResponse
import com.knk.manyak.credit.dto.CreditTransactionPageResponse
import com.knk.manyak.credit.service.AttendanceRewardService
import com.knk.manyak.credit.service.CreditTransactionHistoryService
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.global.security.CurrentUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@Tag(name = "Credits", description = "이프 API")
@SecurityRequirement(name = "bearerAuth") // 크레딧 API는 인증 필수(스웨거 자물쇠·Authorize 대상). 스킴은 OpenApiConfig.SECURITY_SCHEME_NAME.
@RestController
@RequestMapping("/api/v1/users/me")
class CreditController(
    private val creditWalletService: CreditWalletService,
    private val attendanceRewardService: AttendanceRewardService,
    private val creditTransactionHistoryService: CreditTransactionHistoryService,
) {

    @Operation(
        summary = "이프 잔액 조회",
        description = "요청자의 현재 이프 잔액을 반환합니다. 지갑이 없으면 0입니다. 인증 필수입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = CreditBalanceResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/credits")
    fun getMyCredits(
        @CurrentUserId userId: Long?,
    ): CreditBalanceResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return CreditBalanceResponse(balance = creditWalletService.balanceOf(ownerId))
    }

    @Operation(
        summary = "출석체크 보상",
        description = "출석 보상 이프를 지급합니다. KST 자정 기준 1일 1회이며, 오늘 이미 받았으면 rewarded=false로 200을 반환합니다(멱등). 인증 필수입니다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "처리 성공(지급 또는 이미 받음)",
                content = [Content(schema = Schema(implementation = CreditAttendanceResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @PostMapping("/credits/attendance")
    fun claimAttendance(
        @CurrentUserId userId: Long?,
    ): CreditAttendanceResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        val outcome = attendanceRewardService.claimDailyAttendance(ownerId)
        return CreditAttendanceResponse(
            rewarded = outcome.rewarded,
            amount = outcome.amount,
            balance = outcome.balance,
        )
    }

    @Operation(
        summary = "이프 이용내역 조회",
        description = """
            요청자의 크레딧 증감 내역을 최신순으로 반환합니다. 인증 필수입니다.

            - `type`: 화면 필터 칩과 같은 값(`ALL`·`SPEND`·`EARN`·`EXPIRE`). 환불(REFUND)은 획득으로 분류하고,
              구매(PURCHASE)는 구매내역 탭 몫이라 `ALL`에서도 제외합니다.
            - `limit`: 1~100으로 보정합니다(기본 50).
            - `cursor`: 이전 응답의 `nextCursor`를 그대로 넘기면 다음 페이지입니다. 다음이 없으면 `nextCursor`는 null입니다.
            - `title`은 관련 스토리 제목이며, 보상·소멸 행이거나 스토리가 삭제됐으면 null입니다.
            - 소멸 행의 `createdAt`은 회수가 기록된 시각이라 실제 만료일과 다릅니다. 날짜 표시는 `expiresAt`을 쓰세요.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = CreditTransactionPageResponse::class))],
            ),
            ApiResponse(
                responseCode = "400",
                description = "커서가 올바르지 않거나 지원하지 않는 type",
                content = [Content(schema = Schema(hidden = true))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "인증 실패(토큰 없음·만료·위조) 또는 사용자 없음",
                content = [Content(schema = Schema(hidden = true))],
            ),
        ],
    )
    @GetMapping("/credits/transactions")
    fun getMyCreditTransactions(
        @CurrentUserId userId: Long?,
        @RequestParam(defaultValue = "ALL") type: String,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): CreditTransactionPageResponse {
        val ownerId = userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증입니다.")
        return creditTransactionHistoryService.history(ownerId, type, limit, cursor)
    }
}
