package com.knk.manyak.credit.service

import com.knk.manyak.auth.port.MeCreditSummaryPort
import com.knk.manyak.auth.port.SignupCreditPort
import com.knk.manyak.credit.dto.CreditReason
import org.springframework.stereotype.Component

/**
 * 계정 모듈의 크레딧 포트 구현(KNK-2064). 기존 서비스에 위임만 한다.
 *
 * `@Transactional`을 달지 않는다. 그래야 [CreditWalletService.reward]가 자기 트랜잭션을 열고,
 * 잔액과 출석 조회는 호출자의 트랜잭션(`PATCH /users/me`)에 참여하거나 각자 읽기 트랜잭션(`GET /auth/me`)을 여는 지금 경계가 유지된다.
 */
@Component
class AccountCreditAdapter(
    private val guestTrialLimitService: GuestTrialLimitService,
    private val creditPolicyService: CreditPolicyService,
    private val creditWalletService: CreditWalletService,
    private val attendanceRewardService: AttendanceRewardService,
) : SignupCreditPort, MeCreditSummaryPort {

    override fun snapshotTrialAtSignup(userId: Long, deviceId: String?): Boolean =
        guestTrialLimitService.snapshotTrialAtSignup(userId, deviceId)

    // 지급량은 운영 중 조정 가능한 정책값이라 매 지급 시 해석한다(KNK-1056).
    override fun rewardSignup(userId: Long, idempotencyKey: String) {
        creditWalletService.reward(
            userId,
            creditPolicyService.amountOf(CreditPolicyKey.SIGNUP_REWARD),
            CreditReason.SIGNUP_REWARD,
            idempotencyKey,
        )
    }

    override fun balanceOf(userId: Long): Long = creditWalletService.balanceOf(userId)

    override fun hasAttendedToday(userId: Long): Boolean = attendanceRewardService.hasAttendedToday(userId)
}
