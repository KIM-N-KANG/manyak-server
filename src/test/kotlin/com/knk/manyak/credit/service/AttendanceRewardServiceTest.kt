package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * AttendanceRewardService 단위 검증(저장소는 mock, 시계는 고정).
 *
 * - 멱등 키는 KST 날짜 기준이다(UTC 날짜와 다를 수 있는 경계도 확인).
 * - reward 결과를 그대로 매핑하되, 미지급 시 amount는 0이다.
 */
class AttendanceRewardServiceTest {

    private val creditWalletService: CreditWalletService = mock(CreditWalletService::class.java)

    // 2026-07-05T16:00Z = KST(+9) 2026-07-06 01:00 → KST 날짜는 07-06(UTC 날짜 07-05와 다른 경계).
    private val clock = Clock.fixed(Instant.parse("2026-07-05T16:00:00Z"), ZoneOffset.UTC)
    private val service = AttendanceRewardService(creditWalletService, attendanceReward = 10, clock = clock)

    @Test
    fun `KST 날짜 기준 멱등 키로 적립하고 결과를 매핑한다`() {
        `when`(creditWalletService.reward(1L, 10, CreditReason.ATTENDANCE_REWARD, "attendance:1:2026-07-06"))
            .thenReturn(RewardOutcome(rewarded = true, balance = 10))

        val outcome = service.claimDailyAttendance(1L)

        assertThat(outcome.rewarded).isTrue()
        assertThat(outcome.amount).isEqualTo(10)
        assertThat(outcome.balance).isEqualTo(10)
    }

    @Test
    fun `오늘 이미 받았으면 rewarded=false고 amount는 0이다`() {
        `when`(creditWalletService.reward(1L, 10, CreditReason.ATTENDANCE_REWARD, "attendance:1:2026-07-06"))
            .thenReturn(RewardOutcome(rewarded = false, balance = 10))

        val outcome = service.claimDailyAttendance(1L)

        assertThat(outcome.rewarded).isFalse()
        assertThat(outcome.amount).isEqualTo(0)
        assertThat(outcome.balance).isEqualTo(10)
    }

    @Test
    fun `hasAttendedToday는 KST 날짜 기준 멱등 키의 적립 여부를 그대로 반환한다`() {
        `when`(creditWalletService.hasTransaction("attendance:1:2026-07-06")).thenReturn(true)

        assertThat(service.hasAttendedToday(1L)).isTrue()
    }

    @Test
    fun `오늘 출석하지 않았으면 hasAttendedToday는 false다`() {
        `when`(creditWalletService.hasTransaction("attendance:1:2026-07-06")).thenReturn(false)

        assertThat(service.hasAttendedToday(1L)).isFalse()
    }
}
