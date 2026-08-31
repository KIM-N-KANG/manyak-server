package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditPolicy
import com.knk.manyak.credit.repository.CreditPolicyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * CreditPolicyService 단위 검증(저장소는 mock, 시계는 테스트가 앞으로 민다).
 *
 * 캐시 TTL·만료 판정·장애 폴백처럼 시점과 쿼리 횟수에 걸린 동작을 여기서 본다.
 * yml 기본값이 실제로 붙는지는 [CreditPolicyServiceIntegrationTest]가 본다.
 */
class CreditPolicyServiceTest {

    private val repository: CreditPolicyRepository = mock(CreditPolicyRepository::class.java)
    private var now: Instant = Instant.parse("2026-08-31T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    private fun service() = CreditPolicyService(
        creditPolicyRepository = repository,
        signupReward = 1000,
        inviteReward = 2000,
        inviteMonthlyCap = 10,
        attendanceReward = 350,
        storyCreationCost = 200,
        chatTurnCost = 20,
        cacheTtl = CACHE_TTL,
        clock = clock,
    )

    @Test
    fun `오버라이드가 없으면 주입된 기본값을 쓴다`() {
        `when`(repository.findAll()).thenReturn(emptyList())
        val service = service()

        assertThat(CreditPolicyKey.entries.associateWith { service.amountOf(it) })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    CreditPolicyKey.SIGNUP_REWARD to 1000L,
                    CreditPolicyKey.INVITE_REWARD to 2000L,
                    CreditPolicyKey.INVITE_MONTHLY_CAP to 10L,
                    CreditPolicyKey.ATTENDANCE_REWARD to 350L,
                    CreditPolicyKey.STORY_CREATION_COST to 200L,
                    CreditPolicyKey.CHAT_TURN_COST to 20L,
                ),
            )
    }

    @Test
    fun `유효한 오버라이드가 있으면 그 값을 쓴다`() {
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "attendance_reward", amount = 700)))

        assertThat(service().amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)
    }

    @Test
    fun `effective_until이 지난 오버라이드는 무시하고 기본값으로 돌아간다`() {
        `when`(repository.findAll()).thenReturn(
            listOf(
                CreditPolicy(
                    policyKey = "attendance_reward",
                    amount = 700,
                    effectiveUntil = now.plusSeconds(30),
                ),
            ),
        )
        val service = service()
        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(700)

        // 캐시 TTL(60초)이 아니라 만료 시각이 지나는 순간 되돌아가야 한다 — 이벤트 종료가 최대 1분 늦으면 안 된다.
        now = now.plusSeconds(31)

        assertThat(service.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
    }

    @Test
    fun `TTL 안에서는 여러 번 읽어도 조회는 1회다`() {
        `when`(repository.findAll()).thenReturn(emptyList())
        val service = service()

        repeat(10) { service.amountOf(CreditPolicyKey.CHAT_TURN_COST) }
        verify(repository, times(1)).findAll()

        // TTL이 지나면 다시 읽는다(운영 SQL 변경이 1분 안에 반영된다).
        now = now.plus(CACHE_TTL).plusSeconds(1)
        service.amountOf(CreditPolicyKey.CHAT_TURN_COST)
        verify(repository, times(2)).findAll()
    }

    @Test
    fun `조회가 실패해도 요청을 막지 않고 직전 스냅샷을 유지한다`() {
        `when`(repository.findAll())
            .thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 5)))
            .thenThrow(IllegalStateException("db down"))
        val service = service()
        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(5)

        now = now.plus(CACHE_TTL).plusSeconds(1)

        assertThat(service.amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(5)
    }

    @Test
    fun `스냅샷 없이 첫 조회부터 실패하면 기본값으로 떨어진다`() {
        `when`(repository.findAll()).thenThrow(IllegalStateException("db down"))

        assertThat(service().amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(20)
    }

    @Test
    fun `최소값 미만 오버라이드는 무시하고 기본값을 쓴다`() {
        // CHECK(0..10000)를 통과하는 amount=0 이 여기까지 온다. 그대로 쓰면 CreditWalletService 의
        // require(amount > 0) 가 터져 채팅 턴 전체가 500 이 된다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "chat_turn_cost", amount = 0)))

        assertThat(service().amountOf(CreditPolicyKey.CHAT_TURN_COST)).isEqualTo(20)
    }

    @Test
    fun `invite_monthly_cap 은 0 오버라이드를 허용한다`() {
        // 상한 0 = 초대자 적립 중단. 기존 코드가 감당하는(집계 0 >= 상한 0 → 초대자 몫만 스킵) 유효한 설정이다.
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "invite_monthly_cap", amount = 0)))

        assertThat(service().amountOf(CreditPolicyKey.INVITE_MONTHLY_CAP)).isZero()
    }

    @Test
    fun `기본값이 최소값 미만이면 부팅에 실패한다`() {
        // 오버라이드와 달리 기본값에는 물러설 곳이 없다. env 로 0 을 넣는 사고는 배포 시점에 드러나야 한다.
        assertThatThrownBy { CreditPolicyService(repository, 0, 2000, 10, 350, 200, 20, CACHE_TTL, clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("signup_reward")
    }

    @Test
    fun `모르는 policy_key 행은 무시한다`() {
        `when`(repository.findAll()).thenReturn(listOf(CreditPolicy(policyKey = "attendanceReward", amount = 700)))

        assertThat(service().amountOf(CreditPolicyKey.ATTENDANCE_REWARD)).isEqualTo(350)
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(60)
    }
}
