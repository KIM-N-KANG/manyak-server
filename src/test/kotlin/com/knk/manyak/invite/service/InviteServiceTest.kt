package com.knk.manyak.invite.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.MonthlyRewardCap
import com.knk.manyak.credit.service.RewardOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * InviteService의 발급·적립 계약을 고정한다(저장소·지갑은 mock — KNK-567 개편).
 *
 * - 발급: 코드가 없으면 지연 생성해 저장하고, 있으면 그대로 반환한다.
 * - 적립: [InviteService.redeem]이 정규화된 코드로 초대자를 찾아 관계를 저장하고 양쪽에 INVITE_REWARD를 위임한다.
 *   월 상한 판정은 초대자 몫에만 [MonthlyRewardCap]으로 위임하고(지갑 락 안에서 원자 판정, 초대자 역할 행만 집계 —
 *   KNK-581), 제출자 몫은 상한 없이 위임한다. 여기서는 올바른 멱등 키·상한·집계 구간 전달까지만 검증하고,
 *   상한 스킵 자체는 지갑 서비스 통합 테스트가 검증한다.
 *
 * 멱등 키는 `invite:{초대자}:{피초대자}:{수혜자}`로 각 수혜자당 1회다.
 * 월 상한 집계 구간은 **적립(redeem) 시점의 KST 월**이다(KNK-567 — 가입 월 귀속 특례 폐기).
 */
class InviteServiceTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val creditWalletService: CreditWalletService = mock(CreditWalletService::class.java)

    private val inviteReward = 500L
    private val inviteMonthlyCap = 10L

    // 기준 시계: KST 2026-07-15 09:00(UTC 00:00). 이 시각이 속한 KST 월 [7/1, 8/1)이 상한 집계 구간이다.
    private val clockInJuly = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC)
    private val service = serviceAt(clockInJuly)

    private val julyStart = kstMonthStart(2026, 7)
    private val augustStart = kstMonthStart(2026, 8)

    private fun serviceAt(clock: Clock): InviteService =
        InviteService(userRepository, creditWalletService, inviteReward, inviteMonthlyCap, clock)

    private fun kstMonthStart(year: Int, month: Int): Instant =
        ZonedDateTime.of(year, month, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()

    // Kotlin non-null 파라미터에 Mockito 매처가 null을 반환해 NPE가 나는 것을 타입만 맞춰 우회한다(mockito-kotlin 미사용).
    private fun anyReason(): CreditReason = any(CreditReason::class.java) ?: CreditReason.INVITE_REWARD

    private fun stubReward(outcome: RewardOutcome) {
        `when`(
            creditWalletService.reward(anyLong(), anyLong(), anyReason(), anyString(), any(), any(), any()),
        ).thenReturn(outcome)
    }

    private fun rewardInvocations() =
        mockingDetails(creditWalletService).invocations.filter { it.method.name == "reward" }

    @Test
    fun `이미 코드가 있으면 그대로 반환하고 새로 발급하지 않는다`() {
        val user = User(id = 1L, nickname = "보유자", inviteCode = "EXIST123")
        `when`(userRepository.findByIdForUpdate(1L)).thenReturn(user)

        val response = service.getOrCreateInvite(1L)

        assertThat(response.inviteCode).isEqualTo("EXIST123")
        verify(userRepository, never()).existsByInviteCode(anyString())
    }

    @Test
    fun `코드가 없으면 지연 발급해 저장한다`() {
        val user = User(id = 1L, nickname = "미발급")
        `when`(userRepository.findByIdForUpdate(1L)).thenReturn(user)
        `when`(userRepository.existsByInviteCode(anyString())).thenReturn(false)

        val response = service.getOrCreateInvite(1L)

        // 8자 코드를 생성해 사용자에 반영하고(더티 체킹으로 저장), 응답에 같은 코드를 쓴다.
        assertThat(response.inviteCode).hasSize(8)
        assertThat(user.inviteCode).isEqualTo(response.inviteCode)
    }

    @Test
    fun `발급 코드는 혼동 문자를 제외한 대문자+숫자 집합만 쓴다`() {
        // KNK-567: 사람이 카카오톡 본문을 보고 타이핑하는 값이라 O·0, I·1·L을 제외한다. 여러 번 생성해 집합 위반을 잡는다.
        `when`(userRepository.existsByInviteCode(anyString())).thenReturn(false)
        repeat(30) { i ->
            val user = User(id = i + 1L, nickname = "발급$i")
            `when`(userRepository.findByIdForUpdate(user.id)).thenReturn(user)

            val response = service.getOrCreateInvite(user.id)

            assertThat(response.inviteCode).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}")
        }
    }

    @Test
    fun `초대 조회는 이번 KST 월 초대 보상 건수와 월 상한을 함께 반환한다`() {
        val user = User(id = 7L, nickname = "진행", inviteCode = "PROG7777")
        `when`(userRepository.findByIdForUpdate(7L)).thenReturn(user)
        // 집계 구간은 시계(2026-07-15)가 속한 KST 월 [7/1, 8/1)이고, 초대자 역할 행만 세도록
        // 멱등 키 접두(invite:{요청자}:)를 전달해야 한다(월 상한 판정과 같은 창·같은 필터 — KNK-581).
        `when`(
            creditWalletService.countRewardsInWindow(7L, CreditReason.INVITE_REWARD, julyStart, augustStart, "invite:7:"),
        ).thenReturn(3L)

        val response = service.getOrCreateInvite(7L)

        assertThat(response.monthlyRewardCount).isEqualTo(3L)
        assertThat(response.monthlyRewardLimit).isEqualTo(inviteMonthlyCap)
    }

    @Test
    fun `redeem은 정규화된 코드로 초대자를 찾아 관계를 저장하고 양쪽에 적립을 위임한다`() {
        val redeemer = User(id = 9L, nickname = "제출자")
        val inviter = User(id = 5L, nickname = "초대자", inviteCode = "GOOD5555")
        `when`(userRepository.findByIdForUpdate(9L)).thenReturn(redeemer)
        `when`(userRepository.findByInviteCode("GOOD5555")).thenReturn(inviter)
        stubReward(RewardOutcome(rewarded = true, balance = 500L))

        val response = service.redeem(9L, "  good5555  ")

        // trim·대문자 정규화된 코드로 조회하고, 관계를 저장하며, 응답은 제출자 적립 결과를 싣는다.
        assertThat(redeemer.inviterUserId).isEqualTo(5L)
        assertThat(response.amount).isEqualTo(inviteReward)
        assertThat(response.balance).isEqualTo(500L)

        // 초대자(5)·제출자(9) 두 번 적립을 위임한다. 멱등 키는 invite:{초대자}:{피초대자}:{수혜자}.
        val rewards = rewardInvocations()
        assertThat(rewards).hasSize(2)

        val inviterReward = rewards.single { it.getArgument<Long>(0) == 5L }
        assertThat(inviterReward.getArgument<Long>(1)).isEqualTo(inviteReward)
        assertThat(inviterReward.getArgument<CreditReason>(2)).isEqualTo(CreditReason.INVITE_REWARD)
        assertThat(inviterReward.getArgument<String>(3)).isEqualTo("invite:5:9:5")
        // 월 상한은 초대자 몫에만 MonthlyRewardCap으로 위임한다(지갑 락 안 판정, 초대자 역할 행만 집계 — KNK-581).
        assertThat(inviterReward.getArgument<MonthlyRewardCap>(6))
            .isEqualTo(MonthlyRewardCap(CreditReason.INVITE_REWARD, inviteMonthlyCap, julyStart, augustStart, "invite:5:"))

        val redeemerReward = rewards.single { it.getArgument<Long>(0) == 9L }
        assertThat(redeemerReward.getArgument<String>(3)).isEqualTo("invite:5:9:9")
        // 제출자 몫은 평생 1회 자격이 유일한 제한이라 월 상한 없이 위임한다(KNK-581).
        assertThat(redeemerReward.getArgument<MonthlyRewardCap?>(6)).isNull()
    }

    @Test
    fun `redeem의 월 상한 집계 구간은 적립 시점의 KST 월이다`() {
        // KNK-567: 월 귀속은 가입 월이 아니라 적립(redeem) 시점의 KST 월이다. 8월에 제출하면 창은 [8/1, 9/1)이어야 한다.
        val redeemer = User(id = 9L, nickname = "제출자")
        val inviter = User(id = 5L, nickname = "초대자", inviteCode = "GOOD5555")
        `when`(userRepository.findByIdForUpdate(9L)).thenReturn(redeemer)
        `when`(userRepository.findByInviteCode("GOOD5555")).thenReturn(inviter)
        stubReward(RewardOutcome(rewarded = true, balance = 500L))

        serviceAt(Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC)).redeem(9L, "GOOD5555")

        // 상한은 초대자 몫에만 걸리므로(KNK-581) 초대자(5) 위임의 집계 창으로 검증한다.
        val inviterReward = rewardInvocations().single { it.getArgument<Long>(0) == 5L }
        assertThat(inviterReward.getArgument<MonthlyRewardCap>(6))
            .isEqualTo(MonthlyRewardCap(CreditReason.INVITE_REWARD, inviteMonthlyCap, augustStart, kstMonthStart(2026, 9), "invite:5:"))
    }

    @Test
    fun `제출자 적립이 스킵되면 amount는 0이고 요청은 성공한다`() {
        // 제출자 몫에 월 상한은 없지만(KNK-581), 멱등 키 중복(재시도 경합) 스킵은 여전히 rewarded=false로 온다.
        val redeemer = User(id = 9L, nickname = "제출자")
        val inviter = User(id = 5L, nickname = "초대자", inviteCode = "GOOD5555")
        `when`(userRepository.findByIdForUpdate(9L)).thenReturn(redeemer)
        `when`(userRepository.findByInviteCode("GOOD5555")).thenReturn(inviter)
        stubReward(RewardOutcome(rewarded = false, balance = 120L))

        val response = service.redeem(9L, "GOOD5555")

        // 적립이 스킵돼도 오류가 아니다. 응답 amount만 0으로 구분하고 잔액은 현재값을 싣는다.
        assertThat(response.amount).isEqualTo(0L)
        assertThat(response.balance).isEqualTo(120L)
        // 관계(평생 1회 소진)는 저장된다.
        assertThat(redeemer.inviterUserId).isEqualTo(5L)
    }
}
