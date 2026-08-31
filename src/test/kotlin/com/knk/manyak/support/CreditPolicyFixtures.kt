package com.knk.manyak.support

import com.knk.manyak.credit.repository.CreditPolicyRepository
import com.knk.manyak.credit.service.CreditPolicyService
import org.mockito.Mockito.mock
import java.time.Duration

/**
 * 오버라이드 행이 없는(= 기본값만 쓰는) [CreditPolicyService]. 단위 테스트가 정책값을 고정할 때 쓴다.
 *
 * 서비스를 mock으로 바꾸지 않고 실물을 쓴다 — 정책 해석 자체가 아니라 "이 수치일 때 도메인이 어떻게 도는가"를
 * 보는 테스트라, 실물이 붙어 있어야 해석 규칙이 바뀌었을 때 여기서도 드러난다.
 * mock 저장소의 findAll()은 Mockito 기본값으로 빈 목록이라 항상 기본값이 나온다.
 */
fun fixedCreditPolicyService(
    signupReward: Long = 1000,
    inviteReward: Long = 2000,
    inviteMonthlyCap: Long = 10,
    attendanceReward: Long = 350,
    storyCreationCost: Long = 200,
    chatTurnCost: Long = 20,
): CreditPolicyService = CreditPolicyService(
    creditPolicyRepository = mock(CreditPolicyRepository::class.java),
    signupReward = signupReward,
    inviteReward = inviteReward,
    inviteMonthlyCap = inviteMonthlyCap,
    attendanceReward = attendanceReward,
    storyCreationCost = storyCreationCost,
    chatTurnCost = chatTurnCost,
    cacheTtl = Duration.ofSeconds(60),
)
