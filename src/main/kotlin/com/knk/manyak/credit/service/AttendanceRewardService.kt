package com.knk.manyak.credit.service

import com.knk.manyak.auth.service.AccountQueryService
import com.knk.manyak.credit.dto.CreditReason
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
 * 멱등 키 `attendance:{보상신원}:{KST날짜}`로 같은 날 중복 지급을 막는다([CreditWalletService.reward]의 유니크 제약·락으로 동시 클릭도 안전).
 * KST 경계 판정을 위해 [Clock]을 주입받는다(테스트에서 고정 시계로 날짜 경계를 검증).
 *
 * 키를 user_id가 아니라 **보상 신원**(`coalesce(reward_identity_user_id, id)` — KNK-1053)으로 잡는다:
 * 탈퇴 후 재가입은 user_id를 갈아치우므로, user_id 키면 같은 사람이 하루에도 재가입만으로 출석 보상을 무한 반복 수령한다.
 * 기존 회원·신규 가입은 `reward_identity_user_id`가 NULL이라 키 문자열이 종전과 같아 원장 호환이 유지된다.
 * 적립은 회원 id를 받아 서비스 안에서 보상 신원을 해석한다. 조회는 세션 응답 조립이 회원 행을 이미 들고 있어 보상 신원을 받는다(KNK-2091).
 */
@Service
class AttendanceRewardService(
    private val creditWalletService: CreditWalletService,
    private val accounts: AccountQueryService,
    // 출석 보상 지급량. 운영 중 조정 가능한 정책값이라 매 지급 시 해석한다(KNK-1056).
    private val creditPolicyService: CreditPolicyService,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Transactional
    fun claimDailyAttendance(userId: Long): AttendanceOutcome {
        // 지급액과 응답 amount가 반드시 같도록 한 번만 읽어 재사용한다.
        val attendanceReward = creditPolicyService.amountOf(CreditPolicyKey.ATTENDANCE_REWARD)
        val outcome = creditWalletService.reward(
            userId = userId,
            amount = attendanceReward,
            reason = CreditReason.ATTENDANCE_REWARD,
            idempotencyKey = idempotencyKeyOf(userId),
        )
        return AttendanceOutcome(
            rewarded = outcome.rewarded,
            // 이번 호출로 실제 적립한 금액. 오늘 이미 받았으면 0.
            amount = if (outcome.rewarded) attendanceReward else 0,
            balance = outcome.balance,
        )
    }

    /**
     * [rewardIdentityId]의 오늘(KST) 출석 적립을 이미 받았는지 조회한다. 적립과 같은 멱등 키를 확인만 할 뿐 적립을 유발하지 않는다(스펙 §4-3-5 B17).
     * 인자는 회원 id가 아니라 보상 신원이다.
     */
    @Transactional(readOnly = true)
    fun hasAttendedToday(rewardIdentityId: Long): Boolean = creditWalletService.hasTransaction(keyOf(rewardIdentityId))

    /** 적립의 키. 회원이 없으면 원래 id로 폴백한다(호출부가 인증으로 이미 걸러낸다). */
    private fun idempotencyKeyOf(userId: Long): String = keyOf(accounts.rewardIdentityOf(userId))

    /** 적립과 조회가 반드시 같은 키를 쓰도록 한 곳에서 만든다. */
    private fun keyOf(rewardIdentityId: Long): String = "attendance:$rewardIdentityId:${LocalDate.now(clock.withZone(SEOUL_ZONE))}"

    private companion object {
        val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
