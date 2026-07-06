package com.knk.manyak.invite.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditTransactionRepository
import com.knk.manyak.credit.service.CreditWalletService
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
 *   보상 수령 계정의 이번 KST 월 INVITE_REWARD 건수가 상한 이상이면 그 쪽은 건너뛴다(오류 아님).
 *
 * 멱등 키는 `invite:{초대자}:{피초대자}:{수혜자}`로 각 수혜자당 1회다.
 */
class InviteServiceTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val creditWalletService: CreditWalletService = mock(CreditWalletService::class.java)
    private val creditTransactionRepository: CreditTransactionRepository = mock(CreditTransactionRepository::class.java)

    private val inviteReward = 500L
    private val inviteMonthlyCap = 10L
    private val baseUrl = "https://test.example/invite"
    // 고정 시계(KST 2026-07-06). 월 경계 판정을 결정적으로 만든다.
    private val fixedClock = Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneOffset.UTC)
    private val service = InviteService(
        userRepository,
        creditWalletService,
        creditTransactionRepository,
        inviteReward,
        inviteMonthlyCap,
        baseUrl,
        fixedClock,
    )

    // fixedClock(KST 2026-07-06) 기준 이번 달 [시작, 다음달 시작) 경계. 월 집계 스텁에 정확한 인자로 사용한다.
    // (Mockito의 Kotlin non-null 파라미터 matcher NPE를 피해 앞선 테스트와 같은 방식으로 실제 값을 그대로 쓴다.)
    private val monthStart = ZonedDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()
    private val monthEnd = ZonedDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant()

    // 기본값: existsByIdempotencyKey는 미스텁 시 false, 월 집계(Long)는 미스텁 시 0을 반환한다(Mockito 기본 응답).
    // 개별 테스트가 필요할 때만 아래 헬퍼로 재정의한다.
    private fun stubMonthlyCount(userId: Long, count: Long) {
        `when`(
            creditTransactionRepository.countByUserIdAndReasonAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId,
                CreditReason.INVITE_REWARD,
                monthStart,
                monthEnd,
            ),
        ).thenReturn(count)
    }

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
        service.rewardInvitePair(inviterId = 9L, inviteeId = 9L)

        verifyNoInteractions(creditWalletService)
    }

    @Test
    fun `초대자와 피초대자 양쪽에 수혜자 id 멱등 키로 적립한다`() {
        service.rewardInvitePair(inviterId = 5L, inviteeId = 9L)

        // 초대자(5)·피초대자(9) 두 번 적립한다. Kotlin non-null 파라미터의 matcher NPE를 피하려
        // 기록된 실제 호출 인자를 직접 읽는다(reward는 refType·refId 기본값까지 6개 인자로 기록됨).
        val rewards = mockingDetails(creditWalletService).invocations.filter { it.method.name == "reward" }
        assertThat(rewards).hasSize(2)

        val inviterReward = rewards.single { it.getArgument<Long>(0) == 5L }
        assertThat(inviterReward.getArgument<Long>(1)).isEqualTo(inviteReward)
        assertThat(inviterReward.getArgument<CreditReason>(2)).isEqualTo(CreditReason.INVITE_REWARD)
        assertThat(inviterReward.getArgument<String>(3)).isEqualTo("invite:5:9:5")

        val inviteeReward = rewards.single { it.getArgument<Long>(0) == 9L }
        assertThat(inviteeReward.getArgument<Long>(1)).isEqualTo(inviteReward)
        assertThat(inviteeReward.getArgument<CreditReason>(2)).isEqualTo(CreditReason.INVITE_REWARD)
        assertThat(inviteeReward.getArgument<String>(3)).isEqualTo("invite:5:9:9")
    }

    @Test
    fun `이미 적립된 쌍이면 월 집계 없이 재조회하지 않고 값싸게 통과한다`() {
        `when`(creditTransactionRepository.existsByIdempotencyKey("invite:5:9:5")).thenReturn(true)
        `when`(creditTransactionRepository.existsByIdempotencyKey("invite:5:9:9")).thenReturn(true)

        service.rewardInvitePair(inviterId = 5L, inviteeId = 9L)

        verifyNoInteractions(creditWalletService)
        // Kotlin non-null 파라미터의 matcher NPE를 피해 기록된 실제 호출을 직접 센다(다른 테스트와 같은 방식).
        val countCalls = mockingDetails(creditTransactionRepository).invocations
            .filter { it.method.name == "countByUserIdAndReasonAndCreatedAtGreaterThanEqualAndCreatedAtLessThan" }
        assertThat(countCalls).isEmpty()
    }

    @Test
    fun `초대자가 이번 달 상한에 도달했으면 초대자 적립은 건너뛰고 피초대자는 그대로 적립한다`() {
        stubMonthlyCount(userId = 5L, count = inviteMonthlyCap)
        stubMonthlyCount(userId = 9L, count = 0L)

        service.rewardInvitePair(inviterId = 5L, inviteeId = 9L)

        val rewards = mockingDetails(creditWalletService).invocations.filter { it.method.name == "reward" }
        assertThat(rewards).hasSize(1)
        assertThat(rewards.single().getArgument<Long>(0)).isEqualTo(9L)
    }

    @Test
    fun `피초대자가 이번 달 상한을 넘었으면 피초대자 적립만 건너뛴다`() {
        stubMonthlyCount(userId = 5L, count = 0L)
        stubMonthlyCount(userId = 9L, count = inviteMonthlyCap + 1)

        service.rewardInvitePair(inviterId = 5L, inviteeId = 9L)

        val rewards = mockingDetails(creditWalletService).invocations.filter { it.method.name == "reward" }
        assertThat(rewards).hasSize(1)
        assertThat(rewards.single().getArgument<Long>(0)).isEqualTo(5L)
    }
}
