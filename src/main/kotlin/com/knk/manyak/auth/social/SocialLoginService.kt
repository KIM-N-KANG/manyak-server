package com.knk.manyak.auth.social

import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.handoff.LoginHandoff
import com.knk.manyak.auth.handoff.LoginHandoffService
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.global.observability.analytics.AnalyticsErrorType
import com.knk.manyak.global.observability.analytics.ServerAnalytics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 소셜 ID 토큰으로 로그인한다(검증 → find-or-create → 우리 토큰 발급). 흐름은 provider와 무관하게 동일하고
 * 검증 파라미터만 갈린다(스펙 §4-5) — provider는 호출 인자로 받아 검증기 선택·계정 조회·분석 이벤트에 흘린다.
 *
 * 1) [SocialIdTokenVerifier]로 ID 토큰을 검증해 [SocialUserInfo]를 얻는다(실패는 401).
 * 2) [SocialAccountRegistrar]로 (provider, providerUserId) 연동을 find-or-create한다.
 *    - 있으면: 연결된 [User]를 재사용하고 `lastLoginAt`을 갱신한다.
 *    - 없으면: [User](랜덤 닉네임·프리셋 이미지, ACTIVE)와 [com.knk.manyak.auth.entity.SocialAccount]를 생성한다.
 * 3) 해석된 [User]에 가입 보상 크레딧을 **멱등하게** 적립한다(KNK-392, 스펙 §4-3-7. 별도 API 없음).
 * 4) [AuthTokenService.issueTokens]로 access+refresh를 발급하고 신규 가입 여부(isNewUser)를 실어 반환한다.
 *
 * 초대 보상은 이 경로와 무관하다 — 코드 입력(POST /users/me/invite/redeem)에서 동기·원자적으로 적립한다
 * (KNK-567 개편. 구 링크 방식의 로그인 inviteCode 제출·self-heal 재적립(KNK-393)은 폐기).
 *
 * 트랜잭션은 [SocialAccountRegistrar]가 가진다(이 빈은 트랜잭션 밖에서 오케스트레이션만 한다).
 * 그래서 동시 첫 로그인으로 생성이 유니크 위반([DataIntegrityViolationException])이면,
 * 실패한 내부 트랜잭션과 무관하게 한 번 더 조회해 상대 요청이 만든 계정을 재사용한다(아래 참고).
 *
 * 가입 보상은 **매 로그인마다** 해석된 User에 시도하되 멱등 키(`signup:{보상신원}` — KNK-1053)로 신원당 최대 1회만 적립한다.
 * 이유(자가 복구): 계정 생성 트랜잭션(REQUIRES_NEW)은 커밋됐는데 보상 적립 전에 실패·크래시가 나면 계정만 남고
 * 보상은 없다. 다음 로그인부턴 [SocialAccountRegistrar.findExistingUser]가 그 계정을 찾아 생성 경로를 다시
 * 타지 않으므로, 보상을 생성 경로에만 두면 유실이 영구화된다. 매 로그인 멱등 적립이면 원장 행이 없는 첫 로그인이
 * 지급하고 이후 로그인은 값싼 no-op(existsByIdempotencyKey → rewarded=false)이라, 유실을 자가 복구하면서도 정확히 1회만 준다.
 */
@Service
class SocialLoginService(
    private val verifiers: Map<SocialProvider, SocialIdTokenVerifier>,
    private val registrar: SocialAccountRegistrar,
    private val authTokenService: AuthTokenService,
    private val creditWalletService: CreditWalletService,
    private val guestTrialLimitService: GuestTrialLimitService,
    private val loginHandoffService: LoginHandoffService,
    private val userRepository: UserRepository,
    private val serverAnalytics: ServerAnalytics,
    // 가입 보상 지급량(스펙 §4-3-7, KNK-477 확정: 500).
    @Value("\${manyak.credit.signup-reward:500}") private val signupReward: Long,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun login(
        provider: SocialProvider,
        idToken: String,
        deviceId: String? = null,
        handoffCode: String? = null,
    ): TokenResponse {
        // 토큰 검증 실패는 로그인 처리 실패로 분석 이벤트를 남긴다(스펙 §6-4-2-8·§6-6-7): 서명·만료·audience 실패는
        // validation, provider 연결·timeout은 network. 아직 회원이 없어 게스트 식별로 발행된다.
        val info = try {
            verifierFor(provider).verify(idToken)
        } catch (e: Exception) {
            serverAnalytics.socialLoginFailed(provider, classifyVerifyError(e))
            throw e
        }
        // 인앱 브라우저에서 넘어온 로그인이면 핸드오프에 보관된 원본 디바이스 ID가 요청 헤더보다 우선한다(스펙 §4-3-5).
        // 외부 브라우저의 새 디바이스 ID로 시드하면 인앱에서 쓴 게스트 사용량이 리셋돼 파밍 우회로가 열린다.
        // 무효·만료 코드는 예외가 아니라 null이므로 헤더 폴백으로 로그인은 정상 진행한다.
        val handoff = handoffCode?.let { loginHandoffService.find(it) }
        // 소비된 핸드오프는 디바이스 ID를 비워 보관하므로(보관 규칙), 빈 값은 "헤더 없음"이 아니라 폴백 대상이다.
        // 빈 문자열을 그대로 넘기면 시드가 우회 시도로 오인해 소진 시드를 비가역 확정한다(§4-3-7).
        val effectiveDeviceId = handoff?.deviceId?.takeIf { it.isNotBlank() } ?: deviceId
        return try {
            val (user, isNewUser) = findOrCreateUser(provider, info)
            // 게스트 시절 디바이스 체험 사용량을 회원 계정으로 1회 스냅샷한다(스펙 §4-3-7 B13 — 게스트로 소진 후 가입해
            // 체험을 초기화하는 파밍 차단). 아직 미스냅샷(member_trial_seeded_at NULL)인 계정만 시도하며, 기존 회원(마이그레이션이
            // 채움)·이미 스냅샷한 계정은 건너뛰어 남은 회원 체험을 훼손하지 않는다. device 헤더가 없으면 소진 시드로 무료 체험을
            // 부여하지 않는다(우회 차단). 완료(true)했을 때만 완료 시각을 기록하고, Redis 장애면 미기록으로 다음 로그인이 재시도한다.
            // 재가입 계정(KNK-1053)은 디바이스를 넘기지 않아 "미증명" 경로로 흘린다 → 한도값 시드 = 무료 체험 미부여.
            // 이전 계정이 회원 체험을 이미 썼는지 알 방법이 없고, 안 쓴 경우까지 부여하면 탈퇴·재가입 반복으로
            // 무료 스토리 1편 + 채팅 5턴을 무한히 얻는다. 과소 부여가 파밍 허용보다 안전하다.
            val snapshotDeviceId = effectiveDeviceId.takeIf { user.rejoinedAt == null }
            val seeded = user.memberTrialSeededAt != null ||
                guestTrialLimitService.snapshotTrialAtSignup(user.id, snapshotDeviceId).also { snapshotted ->
                    if (snapshotted) userRepository.markMemberTrialSeeded(user.id, Instant.now())
                }
            // 매 로그인마다 시도하되 멱등 키로 회원당 1회만 적립한다(생성 시 유실된 보상까지 자가 복구).
            rewardSignup(user)
            // 핸드오프 소비(= 게스트 데이터 이관)는 이 호출이 겸한다. 별도 호출로 미루면 "로그인 → 이관 → 복귀"
            // 순서 경쟁이 생기고, 시드는 이미 확정된 뒤라 되돌릴 수 없다(스펙 §4-3-5). 이미 소비된 코드는 멱등 no-op.
            //
            // 단, 시드가 실패했으면(Redis 장애 → 미시드로 남아 다음 로그인이 재시도) 소비하지 않는다.
            // 소비는 보관 규칙상 원본 디바이스 ID를 지우므로, 여기서 소비해 버리면 재시도가 인앱 디바이스를
            // 잃고 외부 브라우저 디바이스로 시드해 게스트 사용량이 리셋되거나 소진으로 잘못 확정된다.
            if (handoff != null && handoffCode != null && seeded) {
                consumeHandoffQuietly(handoffCode, handoff, user.id)
            }
            // 신규 가입 여부를 응답에 실어 프론트엔드 온보딩(초대 코드 입력 스텝, KNK-567)이 판정하게 한다.
            val tokens = authTokenService.issueTokens(user).copy(isNewUser = isNewUser)
            serverAnalytics.socialLoginSucceeded(provider, user.publicId.toString(), isNewUser)
            tokens
        } catch (e: Exception) {
            // 검증 통과 후 사용자 저장·보상·토큰 발급 중 실패는 서버 내부 처리 실패로 분류한다(스펙 §6-6-7).
            serverAnalytics.socialLoginFailed(provider, AnalyticsErrorType.SERVER)
            throw e
        }
    }

    /**
     * provider의 검증기를 찾는다. 배선이 없는 provider(APPLE·NAVER는 enum에만 예약 — 스펙 §4-5)는
     * 토큰을 보지 않고 401로 막는다(fail-closed). 검증 없는 로그인 경로가 열리는 것보다 거부가 안전하다.
     */
    private fun verifierFor(provider: SocialProvider): SocialIdTokenVerifier =
        verifiers[provider]
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "지원하지 않는 로그인 방식입니다.")

    /**
     * 토큰 검증 예외를 분석용 error_type으로 분류한다(스펙 §6-6-7). verifier는 잘못된 토큰과 **provider JWK 조회 네트워크
     * 실패를 모두 401(ResponseStatusException)로 감싸므로**, 원인 체인을 먼저 훑어 연결·소켓·원격 키 소스 실패면 network로 본다
     * (Codex P2). 그 외 401은 서명·만료·audience 검증 실패라 validation, ResponseStatusException이 아니면 외부 호출 성격상 network.
     */
    private fun classifyVerifyError(e: Exception): AnalyticsErrorType {
        if (AnalyticsErrorType.fromThrowable(e) == AnalyticsErrorType.NETWORK) return AnalyticsErrorType.NETWORK
        return if (e is ResponseStatusException) AnalyticsErrorType.VALIDATION else AnalyticsErrorType.NETWORK
    }

    /**
     * 연동을 찾으면 그 User를, 없으면 새로 만들어 반환한다(find-or-create).
     *
     * 동시 첫 로그인 경합: 두 요청이 모두 조회에서 놓치고 둘 다 생성을 시도하면, 한쪽은
     * 유니크 위반으로 실패한다. 이때 [SocialAccountRegistrar.createUserAndAccount]는 독립 트랜잭션이라
     * 그 실패가 여기(트랜잭션 밖)로 전파돼도 우리는 rollback-only에 걸리지 않는다. 한 번 더 조회하면
     * 이번엔 상대 요청이 커밋한 계정이 보이므로 그 User를 재사용한다(500 대신 정상 로그인).
     * 재조회로도 못 찾으면 일시적 경합이 아니라 실제 정합성 문제이므로 원 예외를 그대로 드러낸다.
     */
    private fun findOrCreateUser(provider: SocialProvider, info: SocialUserInfo): Pair<User, Boolean> {
        val now = Instant.now()
        registrar.findExistingUser(provider, info, now)?.let { return it to false }

        return try {
            registrar.createUserAndAccount(provider, info, now) to true
        } catch (ex: DataIntegrityViolationException) {
            // 경합으로 상대가 먼저 생성·커밋했을 수 있다. 새 조회 시점(now)으로 재시도한다.
            // 이 경로는 상대가 만든 기존 계정 재사용이므로 신규 아님(is_new_user=false).
            (registrar.findExistingUser(provider, info, Instant.now()) ?: throw ex) to false
        }
    }

    /**
     * 핸드오프를 소비하되 **실패가 로그인을 막지 않게** 한다(스펙 §4-3-5 — 예외로 실패하면 코드는 미소비로 남아
     * 만료 전까지 재시도할 수 있다).
     *
     * 이관은 로그인의 부가 작업이지 전제가 아니다. 정지 계정의 이관은 403인데(§4-5 B20) 로그인 자체는 허용되므로,
     * 소비 예외를 그대로 전파하면 정지 안내 화면에 도달할 로그인마저 실패한다. 이관 트랜잭션은
     * [com.knk.manyak.migration.service.GuestDataMigrationService] 안에서 닫히므로, 여기서 삼켜도 시도 횟수는
     * 롤백돼 소모되지 않는다.
     */
    private fun consumeHandoffQuietly(handoffCode: String, handoff: LoginHandoff, userId: Long) {
        try {
            loginHandoffService.consume(handoffCode, handoff, userId)
        } catch (ex: Exception) {
            // 코드 원문은 남기지 않는다(로그 금지 규칙) — 상관관계는 분석용 handoffId로 잇는다.
            logger.warn("핸드오프 소비 실패 — 로그인은 계속, 코드는 미소비로 유지: handoffId={}", handoff.handoffId, ex)
        }
    }

    /**
     * 회원에게 가입 보상을 멱등하게 적립한다(매 로그인 호출). `signup:{보상신원}` 멱등 키로 신원당 최대 1회만 지급한다.
     * 원장 행이 없는 첫 로그인이 적립하고, 이후 로그인은 값싼 no-op(rewarded=false)이다.
     *
     * 키의 스코프가 user_id가 아니라 [User.rewardIdentityUserId](NULL이면 자기 자신)인 이유(KNK-1053):
     * 재가입은 user_id를 갈아치우므로 user_id 키는 재가입마다 새 키가 되어 보상이 리셋된다. 루트에 고정하면
     * "재가입이면 무조건 스킵"보다 정확하다 — 최초 계정이 크래시로 보상을 못 받았으면 재가입 계정이 받는다.
     * 기존 회원·순수 신규 가입은 NULL이라 키 문자열이 종전(`signup:{id}`)과 같아 원장 호환이 유지된다.
     */
    private fun rewardSignup(user: User) {
        creditWalletService.reward(
            user.id,
            signupReward,
            CreditReason.SIGNUP_REWARD,
            "signup:${user.rewardIdentityUserId ?: user.id}",
        )
    }
}
