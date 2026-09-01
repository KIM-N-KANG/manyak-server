package com.knk.manyak.credit.controller

import com.knk.manyak.credit.dto.CreditPolicyResponse
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 이프 적립·소모 수치 조회(KNK-1056 후속).
 *
 * 수치는 `credit_policies` 오버라이드로 릴리스 없이 바뀐다(출시 이벤트 2배 지급 등). 화면이 값을 하드코딩하면
 * 바꾼 순간 안내와 실제 지급이 어긋나므로, 클라이언트가 지금 유효한 값을 읽을 수 있어야 한다.
 * ("출석하면 얼마 받는지"를 버튼을 누르기 전에 보여주는 것이 최초 요구다.)
 *
 * 무인증이다 — 로그인 전 서비스 안내 화면도 같은 값을 쓰고, 비밀도 아니다(지급 응답에 그대로 실린다).
 * 잔액·이용내역과 달리 요청자별 데이터가 없어 `/api/v1/users/me` 아래가 아니라 별도 경로에 둔다.
 *
 * DB를 만지지 않는다 — [CreditPolicyService.amountOf]는 메모리 스냅샷 읽기다.
 */
@Tag(name = "Credits", description = "이프 API")
@RestController
class CreditPolicyController(
    private val creditPolicyService: CreditPolicyService,
) {

    @Operation(
        summary = "이프 적립·소모 수치 조회",
        description = """
            현재 유효한 이프 수치를 반환합니다. 인증이 필요 없습니다.

            - 운영 중 이벤트로 바뀔 수 있으니 화면에 하드코딩하지 말고 이 값을 표시하세요.
            - `inviteMonthlyCap`만 이프가 아니라 월 적립 **횟수**입니다.
            - 변경 반영은 최대 1분 지연될 수 있습니다(서버가 정책 스냅샷을 주기 갱신합니다).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(schema = Schema(implementation = CreditPolicyResponse::class))],
            ),
        ],
    )
    @GetMapping("/api/v1/credits/policies")
    fun getCreditPolicies(): CreditPolicyResponse =
        CreditPolicyResponse(
            signupReward = creditPolicyService.amountOf(CreditPolicyKey.SIGNUP_REWARD),
            inviteReward = creditPolicyService.amountOf(CreditPolicyKey.INVITE_REWARD),
            inviteMonthlyCap = creditPolicyService.amountOf(CreditPolicyKey.INVITE_MONTHLY_CAP),
            attendanceReward = creditPolicyService.amountOf(CreditPolicyKey.ATTENDANCE_REWARD),
            storyCreationCost = creditPolicyService.amountOf(CreditPolicyKey.STORY_CREATION_COST),
            chatTurnCost = creditPolicyService.amountOf(CreditPolicyKey.CHAT_TURN_COST),
        )
}
