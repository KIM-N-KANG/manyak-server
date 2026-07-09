package com.knk.manyak.invite.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.MonthlyRewardCap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * InviteService의 발급·적립 계약을 고정한다(저장소·지갑은 mock).
 *
 * - 발급: 코드가 없으면 지연 생성해 저장하고, 있으면 그대로 링크를 만든다.
 * - 적립: 신규 가입 경로에서만 호출되며, 코드의 유효성(존재·비공백·타인)만 걸러 양쪽에 INVITE_REWARD를 준다.
 *   월 상한 판정은 [CreditWalletService.reward]에 [MonthlyRewardCap]으로 위임한다(지갑 락 안에서 원자 판정).
 *   InviteService는 올바른 멱등 키·상한·집계 구간을 전달하는지까지만 검증하고, 상한 스킵 자체는 지갑 서비스 통합 테스트가 검증한다.
 *
 * 멱등 키는 `invite:{초대자}:{피초대자}:{수혜자}`로 각 수혜자당 1회다.
 * 월 상한 집계 구간은 피초대자 가입 월(KST) 기준으로 고정한다(재시도가 상한 초과를 이월하지 않도록).
 */
class InviteServiceTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val creditWalletService: CreditWalletService = mock(CreditWalletService::class.java)

    private val inviteReward = 500L
    private val inviteMonthlyCap = 10L
    private val baseUrl = "https://test.example/invite"
    // 피초대자 가입 시각(KST 2026-07-06 09:00 = UTC 00:00). 이 값이 속한 KST 월 [시작, 다음달 시작)이 상한 집계 구간이다.
    private val inviteeCreatedAt = Instant.parse("2026-07-06T00:00:00Z")
    // 기본 시계는 피초대자 가입 월(2026-07) 안이라 자가복구 재시도 게이트를 통과한다.
    private val clockInSignupMonth = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
    private val service = InviteService(
        userRepository,
        creditWalletService,
        inviteReward,
        inviteMonthlyCap,
        baseUrl,
        clockInSignupMonth,
    )

    private val monthStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()
    private val monthEnd = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()

    /** 가입 월이 지난 시각의 시계로 만든 서비스(재시도 게이트가 스킵해야 하는 경우 검증용). */
    private fun serviceAt(clockInstant: Instant): InviteService = InviteService(
        userRepository,
        creditWalletService,
        inviteReward,
        inviteMonthlyCap,
        baseUrl,
        Clock.fixed(clockInstant, ZoneOffset.UTC),
    )

    @Test
    fun `이미 코드가 있으면 그대로 링크를 만들고 새로 발급하지 않는다`() {
        val user = User(id = 1L, nickname = "보유자", inviteCode = "EXIST123")
        `when`(userRepository.findByIdForUpdate(1L)).thenReturn(user)

        val response = service.getOrCreateInvite(1L)

        assertThat(response.inviteCode).isEqualTo("EXIST123")
        assertThat(response.inviteUrl).isEqualTo("https://test.example/invite/EXIST123")
        verify(userRepository, never()).existsByInviteCode(anyString())
    }

    @Test
    fun `코드가 없으면 지연 발급해 저장하고 링크를 만든다`() {
        val user = User(id = 1L, nickname = "미발급")
        `when`(userRepository.findByIdForUpdate(1L)).thenReturn(user)
        `when`(userRepository.existsByInviteCode(anyString())).thenReturn(false)

        val response = service.getOrCreateInvite(1L)

        // 8자 코드를 생성해 사용자에 반영하고(더티 체킹으로 저장), 응답·링크에 같은 코드를 쓴다.
        assertThat(response.inviteCode).hasSize(8)
        assertThat(user.inviteCode).isEqualTo(response.inviteCode)
        assertThat(response.inviteUrl).isEqualTo("https://test.example/invite/${response.inviteCode}")
    }

    @Test
    fun `초대 조회는 이번 KST 월 초대 보상 건수와 월 상한을 함께 반환한다`() {
        val user = User(id = 7L, nickname = "진행", inviteCode = "PROG7777")
        `when`(userRepository.findByIdForUpdate(7L)).thenReturn(user)
        // 집계 구간은 시계(2026-07-15)가 속한 KST 월 [7/1, 8/1)이어야 한다(월 상한 판정과 같은 창).
        `when`(
            creditWalletService.countRewardsInWindow(7L, CreditReason.INVITE_REWARD, monthStart, monthEnd),
        ).thenReturn(3L)

        val response = service.getOrCreateInvite(7L)

        assertThat(response.monthlyRewardCount).isEqualTo(3L)
        assertThat(response.monthlyRewardLimit).isEqualTo(inviteMonthlyCap)
    }

    @Test
    fun `초대 코드가 없으면(미제출) 초대자를 해석하지 않는다`() {
        assertThat(service.resolveInviterId(null)).isNull()
        verifyNoInteractions(creditWalletService)
    }

    @Test
    fun `초대 코드가 공백뿐이면 초대자를 해석하지 않는다`() {
        assertThat(service.resolveInviterId("   ")).isNull()
        verify(userRepository, never()).findByInviteCode(anyString())
    }

    @Test
    fun `초대 코드가 어떤 사용자와도 매칭되지 않으면 null이다`() {
        `when`(userRepository.findByInviteCode("UNKNOWN")).thenReturn(null)

        assertThat(service.resolveInviterId("UNKNOWN")).isNull()
    }

    @Test
    fun `유효한 코드면 초대자 id를 해석한다(앞뒤 공백 무시)`() {
        val inviter = User(id = 5L, nickname = "초대자", inviteCode = "GOOD5555")
        `when`(userRepository.findByInviteCode("GOOD5555")).thenReturn(inviter)

        assertThat(service.resolveInviterId("  GOOD5555  ")).isEqualTo(5L)
    }

    @Test
    fun `초대자와 피초대자가 같으면 적립하지 않는다`() {
        service.rewardInvitePair(inviterId = 9L, inviteeId = 9L, inviteeCreatedAt = inviteeCreatedAt)

        verifyNoInteractions(creditWalletService)
    }

    @Test
    fun `초대자와 피초대자 양쪽에 수혜자 id 멱등 키와 피초대자 가입월 상한으로 적립을 위임한다`() {
        service.rewardInvitePair(inviterId = 5L, inviteeId = 9L, inviteeCreatedAt = inviteeCreatedAt)

        // 초대자(5)·피초대자(9) 두 번 적립을 위임한다. Kotlin non-null 파라미터의 matcher NPE를 피하려
        // 기록된 실제 호출 인자를 직접 읽는다(reward는 refType·refId·monthlyCap 기본값까지 7개 인자로 기록됨).
        val rewards = mockingDetails(creditWalletService).invocations.filter { it.method.name == "reward" }
        assertThat(rewards).hasSize(2)

        val expectedCap = MonthlyRewardCap(CreditReason.INVITE_REWARD, inviteMonthlyCap, monthStart, monthEnd)

        val inviterReward = rewards.single { it.getArgument<Long>(0) == 5L }
        assertThat(inviterReward.getArgument<Long>(1)).isEqualTo(inviteReward)
        assertThat(inviterReward.getArgument<CreditReason>(2)).isEqualTo(CreditReason.INVITE_REWARD)
        assertThat(inviterReward.getArgument<String>(3)).isEqualTo("invite:5:9:5")
        // 월 상한은 CreditWalletService.reward가 지갑 락 안에서 판정하도록 MonthlyRewardCap으로 위임한다(경합 없는 원자 판정).
        assertThat(inviterReward.getArgument<MonthlyRewardCap>(6)).isEqualTo(expectedCap)

        val inviteeReward = rewards.single { it.getArgument<Long>(0) == 9L }
        assertThat(inviteeReward.getArgument<Long>(1)).isEqualTo(inviteReward)
        assertThat(inviteeReward.getArgument<CreditReason>(2)).isEqualTo(CreditReason.INVITE_REWARD)
        assertThat(inviteeReward.getArgument<String>(3)).isEqualTo("invite:5:9:9")
        assertThat(inviteeReward.getArgument<MonthlyRewardCap>(6)).isEqualTo(expectedCap)
    }

    @Test
    fun `피초대자 가입 월이 지난 재시도는 적립을 시도하지 않는다`() {
        // 가입은 7월인데 로그인(now)이 8월이면, 재시도 게이트가 막아 어느 쪽도 적립하지 않는다.
        // (상한 초과 스킵이 다음 달로 이월되지 않고, 지급 created_at이 항상 가입 월에 머물러 집계와 일치하도록 하는 계약)
        val augustService = serviceAt(Instant.parse("2026-08-15T00:00:00Z"))

        augustService.rewardInvitePair(inviterId = 5L, inviteeId = 9L, inviteeCreatedAt = inviteeCreatedAt)

        verifyNoInteractions(creditWalletService)
    }
}
