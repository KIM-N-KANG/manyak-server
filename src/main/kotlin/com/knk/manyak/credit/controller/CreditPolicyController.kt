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
 * DB를 만지지 않는다 — [CreditPolicyService.effectiveAmounts]는 메모리 스냅샷 읽기다.
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
            - `inviteMonthlyCap`만 이프가 아니라 월 적립 **횟수**이며, **초대자 몫에만** 걸립니다(제출자 몫은 상한 없음).
            - `storyCreationCost`는 **간편 제작**에만 듭니다(일반 제작은 무료).
            - `storyCreationCost`·`chatTurnCost`는 회원의 무료 체험 잔여를 소진한 뒤 적용되는 단가입니다(게스트는 디바이스 한도를 씁니다).
            - 변경 반영은 서버의 정책 스냅샷 갱신 주기를 따릅니다(기본 설정 기준 약 1분).
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
    fun getCreditPolicies(): CreditPolicyResponse {
        // 6종을 하나씩 읽으면 그 사이 갱신에 옛 값과 새 값이 섞이므로 한 스냅샷에서 모두 꺼낸다.
        val amounts = creditPolicyService.effectiveAmounts()
        return CreditPolicyResponse(
            signupReward = amounts.getValue(CreditPolicyKey.SIGNUP_REWARD),
            inviteReward = amounts.getValue(CreditPolicyKey.INVITE_REWARD),
            inviteMonthlyCap = amounts.getValue(CreditPolicyKey.INVITE_MONTHLY_CAP),
            attendanceReward = amounts.getValue(CreditPolicyKey.ATTENDANCE_REWARD),
            storyCreationCost = amounts.getValue(CreditPolicyKey.STORY_CREATION_COST),
            chatTurnCost = amounts.getValue(CreditPolicyKey.CHAT_TURN_COST),
        )
    }
}
