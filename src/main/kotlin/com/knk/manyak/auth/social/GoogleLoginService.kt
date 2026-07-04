package com.knk.manyak.auth.social

import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
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
 *      **최초 생성 경로에서만** 가입 보상 크레딧을 적립한다(KNK-392, 스펙 §4-3-7. 별도 API 없음).
 * 3) [AuthTokenService.issueTokens]로 access+refresh를 발급해 반환한다.
 *
 * 트랜잭션은 [GoogleAccountRegistrar]가 가진다(이 빈은 트랜잭션 밖에서 오케스트레이션만 한다).
 * 그래서 동시 첫 로그인으로 생성이 유니크 위반([DataIntegrityViolationException])이면,
 * 실패한 내부 트랜잭션과 무관하게 한 번 더 조회해 상대 요청이 만든 계정을 재사용한다(아래 참고).
 */
@Service
class GoogleLoginService(
    private val verifier: GoogleIdTokenVerifier,
    private val registrar: GoogleAccountRegistrar,
    private val authTokenService: AuthTokenService,
    private val creditWalletService: CreditWalletService,
    // 가입 보상 지급량. 스펙상 지급량 미정(계획)이라 플레이스홀더 기본값을 두고 설정으로 덮어쓴다.
    @Value("\${manyak.credit.signup-reward:100}") private val signupReward: Long,
) {

    fun login(idToken: String): TokenResponse {
        val info = verifier.verify(idToken)
        val user = findOrCreateUser(info)
        return authTokenService.issueTokens(user)
    }

    /**
     * 연동을 찾으면 그 User를, 없으면 새로 만들어 반환한다.
     *
     * 동시 첫 로그인 경합: 두 요청이 모두 조회에서 놓치고 둘 다 생성을 시도하면, 한쪽은
     * 유니크 위반으로 실패한다. 이때 [GoogleAccountRegistrar.createUserAndAccount]는 독립 트랜잭션이라
     * 그 실패가 여기(트랜잭션 밖)로 전파돼도 우리는 rollback-only에 걸리지 않는다. 한 번 더 조회하면
     * 이번엔 상대 요청이 커밋한 계정이 보이므로 그 User를 재사용한다(500 대신 정상 로그인).
     * 재조회로도 못 찾으면 일시적 경합이 아니라 실제 정합성 문제이므로 원 예외를 그대로 드러낸다.
     *
     * 가입 보상은 **여기 최초 생성 성공 분기에서만** 적립한다. 기존 사용자 재로그인이나
     * 경합 재조회로 상대 계정을 재사용한 경우는 보상하지 않는다. 멱등 키(signup:{userId})가
     * 재시도 중복 적립도 막으므로, 생성 후 발급 실패로 재로그인해도 두 번 지급되지 않는다.
     */
    private fun findOrCreateUser(info: SocialUserInfo): User {
        val now = Instant.now()
        registrar.findExistingUser(info, now)?.let { return it }

        return try {
            registrar.createUserAndAccount(info, now).also { rewardSignup(it) }
        } catch (ex: DataIntegrityViolationException) {
            // 경합으로 상대가 먼저 생성·커밋했을 수 있다. 새 조회 시점(now)으로 재시도한다(보상 없음).
            registrar.findExistingUser(info, Instant.now()) ?: throw ex
        }
    }

    /** 최초 생성된 회원에게 가입 보상을 적립한다. signup:{userId} 멱등 키로 재시도 중복 적립을 막는다. */
    private fun rewardSignup(newUser: User) {
        creditWalletService.reward(
            newUser.id,
            signupReward,
            CreditReason.SIGNUP_REWARD,
            "signup:${newUser.id}",
        )
    }
}
