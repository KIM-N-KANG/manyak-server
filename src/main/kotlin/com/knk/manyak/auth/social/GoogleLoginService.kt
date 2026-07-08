package com.knk.manyak.auth.social

import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.invite.service.InviteService
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Google ID 토큰으로 로그인한다(검증 → find-or-create → 우리 토큰 발급).
 *
 * 1) [GoogleIdTokenVerifier]로 ID 토큰을 검증해 [SocialUserInfo]를 얻는다(실패는 401).
 * 2) [GoogleAccountRegistrar]로 (GOOGLE, providerUserId) 연동을 find-or-create한다.
 *    - 있으면: 연결된 [User]를 재사용하고 `lastLoginAt`을 갱신한다.
 *    - 없으면: [User](nickname=name→email local-part→"사용자", 50자 제한, ACTIVE)와 [SocialAccount]를 생성한다.
 * 3) 해석된 [User]에 가입 보상 크레딧을 **멱등하게** 적립한다(KNK-392, 스펙 §4-3-7. 별도 API 없음).
 * 3-1) 초대 보상(KNK-393): 신규 생성 시 `inviteCode`의 초대자를 해석해 **생성 트랜잭션에 함께 영속**하고,
 *      영속된 초대자 관계가 있으면 초대자·피초대자 양쪽에 **매 로그인 멱등하게** 적립한다. 관계는 생성 경로에서만
 *      세팅되므로 "이미 가입된 계정의 코드 제출은 무시"가 보장되고, 매 로그인 재적립이 유실을 자가 복구한다.
 * 4) [AuthTokenService.issueTokens]로 access+refresh를 발급해 반환한다.
 *
 * 트랜잭션은 [GoogleAccountRegistrar]가 가진다(이 빈은 트랜잭션 밖에서 오케스트레이션만 한다).
 * 그래서 동시 첫 로그인으로 생성이 유니크 위반([DataIntegrityViolationException])이면,
 * 실패한 내부 트랜잭션과 무관하게 한 번 더 조회해 상대 요청이 만든 계정을 재사용한다(아래 참고).
 *
 * 가입 보상은 **매 로그인마다** 해석된 User에 시도하되 멱등 키(signup:{userId})로 회원당 최대 1회만 적립한다.
 * 이유(자가 복구): 계정 생성 트랜잭션(REQUIRES_NEW)은 커밋됐는데 보상 적립 전에 실패·크래시가 나면 계정만 남고
 * 보상은 없다. 다음 로그인부턴 [GoogleAccountRegistrar.findExistingUser]가 그 계정을 찾아 생성 경로를 다시
 * 타지 않으므로, 보상을 생성 경로에만 두면 유실이 영구화된다. 매 로그인 멱등 적립이면 원장 행이 없는 첫 로그인이
 * 지급하고 이후 로그인은 값싼 no-op(existsByIdempotencyKey → rewarded=false)이라, 유실을 자가 복구하면서도 정확히 1회만 준다.
 */
@Service
class GoogleLoginService(
    private val verifier: GoogleIdTokenVerifier,
    private val registrar: GoogleAccountRegistrar,
    private val authTokenService: AuthTokenService,
    private val creditWalletService: CreditWalletService,
    private val inviteService: InviteService,
    private val guestTrialLimitService: GuestTrialLimitService,
    // 가입 보상 지급량(스펙 §4-3-7, KNK-477 확정: 500).
    @Value("\${manyak.credit.signup-reward:500}") private val signupReward: Long,
) {

    fun login(idToken: String, inviteCode: String? = null, deviceId: String? = null): TokenResponse {
        val info = verifier.verify(idToken)
        val user = findOrCreateUser(info, inviteCode)
        // 디바이스 체험 사용량을 회원 계정으로 스냅샷한다(스펙 §4-3-7 B13 — 게스트로 소진 후 가입해 체험을 초기화하는
        // 파밍 차단). 미설정 시에만 시드하는 멱등 연산이라 매 로그인에서 호출해도 안전하다: 가입 직후 실패로 놓쳐도
        // 다음 로그인이 재시도하고, 이미 시드/소진된 계정은 덮어쓰지 않는다(신규 여부에 묶지 않아 유실을 자가 복구).
        if (!deviceId.isNullOrBlank()) {
            guestTrialLimitService.syncTrialFromDeviceIfUnset(user.id, deviceId)
        }
        // 매 로그인마다 시도하되 멱등 키로 회원당 1회만 적립한다(생성 시 유실된 보상까지 자가 복구).
        rewardSignup(user)
        // 초대 보상: 영속된 초대자 관계가 있으면 매 로그인 멱등 재적립한다(가입 보상과 동일한 자가 복구).
        // 관계는 신규 생성 경로에서만 세팅되므로 "이미 가입된 계정의 코드 제출은 무시"가 보장된다.
        // 월 상한 집계는 피초대자 가입 월(user.createdAt) 기준으로 고정돼, 재시도가 상한 초과를 다음 달로 이월하지 않는다.
        user.inviterUserId?.let { inviterId -> inviteService.rewardInvitePair(inviterId, user.id, user.createdAt) }
        return authTokenService.issueTokens(user)
    }

    /**
     * 연동을 찾으면 그 User를, 없으면 새로 만들어 반환한다(find-or-create).
     *
     * 신규 생성 시 [inviteCode]의 초대자를 지금 해석해 생성 트랜잭션에 함께 영속한다. 코드가 유효할 때만
     * 관계가 남고, 기존 사용자 로그인은 조회에서 바로 반환하므로 코드를 해석하지 않는다(관계 세팅 없음 → 무시).
     *
     * 동시 첫 로그인 경합: 두 요청이 모두 조회에서 놓치고 둘 다 생성을 시도하면, 한쪽은
     * 유니크 위반으로 실패한다. 이때 [GoogleAccountRegistrar.createUserAndAccount]는 독립 트랜잭션이라
     * 그 실패가 여기(트랜잭션 밖)로 전파돼도 우리는 rollback-only에 걸리지 않는다. 한 번 더 조회하면
     * 이번엔 상대 요청이 커밋한 계정이 보이므로 그 User를 재사용한다(500 대신 정상 로그인).
     * 재조회로도 못 찾으면 일시적 경합이 아니라 실제 정합성 문제이므로 원 예외를 그대로 드러낸다.
     */
    private fun findOrCreateUser(info: SocialUserInfo, inviteCode: String?): User {
        val now = Instant.now()
        registrar.findExistingUser(info, now)?.let { return it }

        val inviterUserId = inviteService.resolveInviterId(inviteCode)
        return try {
            registrar.createUserAndAccount(info, now, inviterUserId)
        } catch (ex: DataIntegrityViolationException) {
            // 경합으로 상대가 먼저 생성·커밋했을 수 있다. 새 조회 시점(now)으로 재시도한다.
            // 재사용 계정의 초대자 관계는 실제로 생성한 쪽이 이미 영속했다(여기선 덮어쓰지 않는다).
            registrar.findExistingUser(info, Instant.now()) ?: throw ex
        }
    }

    /**
     * 회원에게 가입 보상을 멱등하게 적립한다(매 로그인 호출). signup:{userId} 멱등 키로 회원당 최대 1회만 지급한다.
     * 원장 행이 없는 첫 로그인이 적립하고, 이후 로그인은 값싼 no-op(rewarded=false)이다.
     */
    private fun rewardSignup(user: User) {
        creditWalletService.reward(
            user.id,
            signupReward,
            CreditReason.SIGNUP_REWARD,
            "signup:${user.id}",
        )
    }
}
