package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditReason
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 출석 보상 지급 결과. [rewarded]가 false면 오늘 이미 받아 이번엔 지급하지 않았다(멱등). */
data class AttendanceOutcome(val rewarded: Boolean, val amount: Long, val balance: Long)

/**
 * 출석체크 보상(스펙 §4-3-7): KST 자정 기준 1일 1회 크레딧을 적립한다.
 *
 * 멱등 키 `attendance:{userId}:{KST날짜}`로 같은 날 중복 지급을 막는다([CreditWalletService.reward]의 유니크 제약·락으로 동시 클릭도 안전).
 * KST 경계 판정을 위해 [Clock]을 주입받는다(테스트에서 고정 시계로 날짜 경계를 검증).
 */
@Service
class AttendanceRewardService(
    private val creditWalletService: CreditWalletService,
    // 출석 보상 지급량(스펙 §4-3-7, KNK-477 확정: 250).
    @param:Value("\${manyak.credit.attendance-reward:250}")
    private val attendanceReward: Long,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Transactional
    fun claimDailyAttendance(userId: Long): AttendanceOutcome {
        val kstDate = LocalDate.now(clock.withZone(SEOUL_ZONE))
        val outcome = creditWalletService.reward(
            userId = userId,
            amount = attendanceReward,
            reason = CreditReason.ATTENDANCE_REWARD,
            idempotencyKey = "attendance:$userId:$kstDate",
        )
        return AttendanceOutcome(
            rewarded = outcome.rewarded,
            // 이번 호출로 실제 적립한 금액. 오늘 이미 받았으면 0.
            amount = if (outcome.rewarded) attendanceReward else 0,
            balance = outcome.balance,
        )
    }

    /** 오늘(KST) 출석 적립을 이미 받았는지 조회한다. 적립과 같은 멱등 키를 확인만 할 뿐 적립을 유발하지 않는다(스펙 §4-3-5 B17). */
    @Transactional(readOnly = true)
    fun hasAttendedToday(userId: Long): Boolean {
        val kstDate = LocalDate.now(clock.withZone(SEOUL_ZONE))
        return creditWalletService.hasTransaction("attendance:$userId:$kstDate")
    }

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
