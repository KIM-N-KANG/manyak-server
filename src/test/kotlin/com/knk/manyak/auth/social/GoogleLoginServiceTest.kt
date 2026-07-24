package com.knk.manyak.auth.social

import com.knk.manyak.auth.dto.TokenResponse
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.handoff.LoginHandoff
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * GoogleLoginService의 오케스트레이션 계약을 고정한다(검증 → find-or-create → 토큰 발급).
 *
 * 영속성은 [GoogleAccountRegistrar] mock으로 대체하고(트랜잭션 경계는 그 빈이 가진다),
 * 토큰 발급은 [AuthTokenService] mock으로 호출 여부만 본다.
 *
 * - 기존 사용자: registrar.findExistingUser가 User를 주면 그대로 토큰을 발급한다.
 * - 신규 사용자: 없으면 createUserAndAccount로 만든다.
 * - 동시 첫 로그인: create가 유니크 위반(DataIntegrityViolationException)이면 재조회로 상대가 만든 계정을 재사용한다.
 * - 검증 실패: verifier가 401을 던지면 전파하고 저장 부작용이 없다.
 *
 * 가입 보상(KNK-392, 스펙 §4-3-7): **매 로그인마다** 해석된 User에 signup:{userId} 멱등 키로 SIGNUP_REWARD를 적립한다.
 * 멱등성은 CreditWalletService가 키로 보장하므로(여기선 호출 여부·인자만 고정), 회원당 최대 1회만 실제 적립된다.
 * 신규·기존·경합 재조회 어느 경로든 reward가 호출되며, 생성 시 유실된 보상도 다음 로그인에 자가 복구된다.
 */
class GoogleLoginServiceTest {

    private val registrar: GoogleAccountRegistrar = mock(GoogleAccountRegistrar::class.java)
    private val authTokenService: AuthTokenService = mock(AuthTokenService::class.java)
    private val creditWalletService: CreditWalletService = mock(CreditWalletService::class.java)
    private val guestTrialLimitService: GuestTrialLimitService = mock(GuestTrialLimitService::class.java)
    private val loginHandoffService: com.knk.manyak.auth.handoff.LoginHandoffService =
        mock(com.knk.manyak.auth.handoff.LoginHandoffService::class.java)
    private val userRepository: com.knk.manyak.auth.repository.UserRepository =
        mock(com.knk.manyak.auth.repository.UserRepository::class.java)
    private val serverAnalytics: com.knk.manyak.global.observability.analytics.ServerAnalytics =
        mock(com.knk.manyak.global.observability.analytics.ServerAnalytics::class.java)

    private val signupReward = 100L

    private fun serviceWith(verifier: GoogleIdTokenVerifier): GoogleLoginService {
        // 로그인 성공 경로는 발급 결과에 copy(isNewUser=...)를 적용하므로, 미스텁 mock의 null 반환이 NPE가 되지 않게 기본 응답을 둔다.
        `when`(authTokenService.issueTokens(anyUser())).thenReturn(TokenResponse("access", "refresh", 1800))
        return GoogleLoginService(
            verifier, registrar, authTokenService, creditWalletService, guestTrialLimitService,
            loginHandoffService, userRepository, serverAnalytics, signupReward,
        )
    }

    private fun fakeVerifier(providerUserId: String = "sub"): GoogleIdTokenVerifier =
        GoogleIdTokenVerifier { SocialUserInfo(providerUserId = providerUserId) }

    // Kotlin non-null 파라미터에 Mockito any()를 쓰면 matcher가 null을 반환해 NPE가 난다.
    // 매처는 stubbing에서 값 자체가 쓰이지 않으므로, 타입만 맞춰주는 헬퍼로 우회한다(mockito-kotlin 미사용).
    private fun anySocialUserInfo(): SocialUserInfo = any(SocialUserInfo::class.java) ?: SocialUserInfo("x")
    private fun anyInstant(): Instant = any(Instant::class.java) ?: Instant.EPOCH
    private fun anyUser(): User = any(User::class.java) ?: User(nickname = "x")

    @Test
    fun `기존 사용자면 재사용해 토큰을 발급하고 새로 만들지 않는다`() {
        val user = User(id = 42L, nickname = "기존닉")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy")

        verify(registrar, never()).createUserAndAccount(anySocialUserInfo(), anyInstant())
        verify(authTokenService).issueTokens(user)
        // 기존 사용자 재로그인도 reward를 호출한다(멱등 키로 실제 적립은 회원당 1회, 유실 자가 복구).
        val reward = mockingDetails(creditWalletService).invocations.single { it.method.name == "reward" }
        assertThat(reward.getArgument<Long>(0)).isEqualTo(42L)
        assertThat(reward.getArgument<String>(3)).isEqualTo("signup:42")
    }

    @Test
    fun `신규 사용자면 생성해 토큰을 발급한다`() {
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)

        serviceWith(fakeVerifier("sub")).login("dummy")

        verify(registrar).createUserAndAccount(anySocialUserInfo(), anyInstant())
        verify(authTokenService).issueTokens(created)
    }

    @Test
    fun `미스냅샷 계정은 체험을 스냅샷하고 완료를 기록한다`() {
        val created = User(id = 7L, nickname = "신규") // memberTrialSeededAt = null
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)
        `when`(guestTrialLimitService.snapshotTrialAtSignup(7L, "dev-1")).thenReturn(true)

        serviceWith(fakeVerifier("sub")).login("dummy", deviceId = "dev-1")

        // 디바이스 체험 사용량을 회원 계정으로 스냅샷하고(B13), 성공했으므로 완료 시각을 기록한다.
        verify(guestTrialLimitService).snapshotTrialAtSignup(7L, "dev-1")
        val mark = mockingDetails(userRepository).invocations.single { it.method.name == "markMemberTrialSeeded" }
        assertThat(mark.getArgument<Long>(0)).isEqualTo(7L)
    }

    @Test
    fun `이미 스냅샷된 계정은 체험 스냅샷을 수행하지 않는다`() {
        // 롤아웃 이전 회원(마이그레이션이 seeded_at을 채움) 등 이미 스냅샷된 계정은 재로그인해도 스냅샷하지 않아
        // 남은 회원 체험을 훼손하지 않는다(B13).
        val user = User(id = 42L, nickname = "기존닉", memberTrialSeededAt = Instant.parse("2026-07-01T00:00:00Z"))
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy", deviceId = "dev-1")

        verifyNoInteractions(guestTrialLimitService)
        assertThat(mockingDetails(userRepository).invocations.none { it.method.name == "markMemberTrialSeeded" }).isTrue()
    }

    @Test
    fun `스냅샷이 Redis 장애로 실패하면 완료를 기록하지 않아 다음 로그인에 재시도된다`() {
        val created = User(id = 7L, nickname = "신규") // memberTrialSeededAt = null
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)
        `when`(guestTrialLimitService.snapshotTrialAtSignup(7L, null)).thenReturn(false) // Redis 장애

        serviceWith(fakeVerifier("sub")).login("dummy")

        // 스냅샷을 시도하되 실패하면 완료를 기록하지 않아, seeded_at이 NULL로 남아 다음 로그인이 재시도한다.
        verify(guestTrialLimitService).snapshotTrialAtSignup(7L, null)
        assertThat(mockingDetails(userRepository).invocations.none { it.method.name == "markMemberTrialSeeded" }).isTrue()
    }

    // ---- 로그인 핸드오프 연동(스펙 §4-3-5, KNK-684) ----

    @Test
    fun `핸드오프의 원본 디바이스 ID가 요청 헤더보다 우선해 시드된다`() {
        // 외부 브라우저의 새 디바이스로 시드하면 인앱에서 쓴 게스트 사용량이 리셋돼 파밍 우회로가 열린다.
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)
        `when`(loginHandoffService.find("code")).thenReturn(LoginHandoff(deviceId = IN_APP_DEVICE))
        `when`(guestTrialLimitService.snapshotTrialAtSignup(7L, IN_APP_DEVICE)).thenReturn(true)

        serviceWith(fakeVerifier("sub")).login("dummy", deviceId = EXTERNAL_DEVICE, handoffCode = "code")

        verify(guestTrialLimitService).snapshotTrialAtSignup(7L, IN_APP_DEVICE)
        assertThat(consumeInvoked()).`as`("시드에 성공했으면 소비한다").isTrue()
    }

    @Test
    fun `시드가 실패하면 핸드오프를 소비하지 않는다`() {
        // 소비는 보관 규칙상 원본 디바이스 ID를 지운다. 미시드로 남아 재시도할 계정의 핸드오프를 소비해 버리면
        // 재시도가 인앱 디바이스를 잃고 외부 디바이스로 시드해 사용량이 리셋되거나 소진으로 잘못 확정된다.
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)
        `when`(loginHandoffService.find("code")).thenReturn(LoginHandoff(deviceId = IN_APP_DEVICE))
        `when`(guestTrialLimitService.snapshotTrialAtSignup(7L, IN_APP_DEVICE)).thenReturn(false) // Redis 장애

        serviceWith(fakeVerifier("sub")).login("dummy", deviceId = EXTERNAL_DEVICE, handoffCode = "code")

        assertThat(consumeInvoked()).`as`("미시드로 남았으므로 소비하지 않는다").isFalse()
    }

    @Test
    fun `무효한 핸드오프 코드는 로그인을 막지 않고 헤더 디바이스로 폴백한다`() {
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)
        `when`(loginHandoffService.find("expired")).thenReturn(null) // 무효·만료는 예외가 아니라 null
        `when`(guestTrialLimitService.snapshotTrialAtSignup(7L, EXTERNAL_DEVICE)).thenReturn(true)

        serviceWith(fakeVerifier("sub")).login("dummy", deviceId = EXTERNAL_DEVICE, handoffCode = "expired")

        verify(guestTrialLimitService).snapshotTrialAtSignup(7L, EXTERNAL_DEVICE)
        assertThat(consumeInvoked()).isFalse()
    }

    @Test
    fun `소비가 실패해도 로그인은 성공한다`() {
        // 정지 계정의 이관은 403이지만 로그인 자체는 허용된다. 소비 예외를 전파하면 정지 안내 화면에
        // 도달할 로그인마저 실패한다(핸드오프는 로그인의 부가 작업이지 전제가 아니다).
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)
        `when`(loginHandoffService.find("code")).thenReturn(LoginHandoff(deviceId = IN_APP_DEVICE))
        `when`(guestTrialLimitService.snapshotTrialAtSignup(7L, IN_APP_DEVICE)).thenReturn(true)
        `when`(loginHandoffService.consume(eqString("code"), anyLoginHandoff(), anyLongArg()))
            .thenThrow(ResponseStatusException(HttpStatus.FORBIDDEN, "정지된 계정입니다."))

        val tokens = serviceWith(fakeVerifier("sub")).login("dummy", deviceId = IN_APP_DEVICE, handoffCode = "code")

        assertThat(tokens.accessToken).isEqualTo("access")
    }

    private fun consumeInvoked(): Boolean =
        mockingDetails(loginHandoffService).invocations.any { it.method.name == "consume" }

    private fun anyLoginHandoff(): LoginHandoff = any(LoginHandoff::class.java) ?: LoginHandoff()
    private fun anyLongArg(): Long = org.mockito.ArgumentMatchers.anyLong()
    private fun eqString(value: String): String = org.mockito.ArgumentMatchers.eq(value) ?: value

    @Test
    fun `신규 사용자면 signup 멱등 키로 가입 보상을 적립한다`() {
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant())).thenReturn(created)

        serviceWith(fakeVerifier("sub")).login("dummy")

        // 생성된 userId로 SIGNUP_REWARD를, signup:{userId} 멱등 키로 설정된 금액만큼 적립한다.
        // Kotlin non-null 파라미터엔 Mockito eq()/captor가 null을 반환해 NPE가 나므로,
        // 기록된 실제 호출 인자를 직접 읽어 검증한다(reward는 refType·refId 기본값까지 6개 인자로 기록됨).
        val invocation = mockingDetails(creditWalletService).invocations
            .single { it.method.name == "reward" }
        assertThat(invocation.getArgument<Long>(0)).isEqualTo(7L)
        assertThat(invocation.getArgument<Long>(1)).isEqualTo(signupReward)
        assertThat(invocation.getArgument<CreditReason>(2)).isEqualTo(CreditReason.SIGNUP_REWARD)
        assertThat(invocation.getArgument<String>(3)).isEqualTo("signup:7")
    }

    @Test
    fun `영속된 초대자 관계가 있어도 로그인은 초대 보상을 적립하지 않는다`() {
        // KNK-567 개편: 로그인 self-heal 재적립(KNK-393)은 폐기됐다. 초대 보상은 redeem 경로에서만 적립한다.
        val user = User(id = 42L, nickname = "피초대자", inviterUserId = 5L)
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy")

        // 가입 보상(signup) 외에 INVITE_REWARD 적립 호출이 없어야 한다.
        val rewards = mockingDetails(creditWalletService).invocations.filter { it.method.name == "reward" }
        assertThat(rewards).hasSize(1)
        assertThat(rewards.single().getArgument<CreditReason>(2)).isEqualTo(CreditReason.SIGNUP_REWARD)
    }

    @Test
    fun `동시 첫 로그인 재조회로 재사용한 User에도 멱등 키로 보상을 적립한다`() {
        val concurrentlyCreated = User(id = 100L, nickname = "상대가만듦")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant()))
            .thenReturn(null)
            .thenReturn(concurrentlyCreated)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant()))
            .thenThrow(DataIntegrityViolationException("uq_social_accounts_provider_user"))

        serviceWith(fakeVerifier("sub")).login("dummy")

        // 경합으로 상대 계정을 재사용해도 해석된 User(100)에 signup:100 키로 reward를 시도한다.
        // 상대 로그인이 이미 적립했다면 멱등 키가 중복을 막는다(실제 적립은 1회).
        val reward = mockingDetails(creditWalletService).invocations.single { it.method.name == "reward" }
        assertThat(reward.getArgument<Long>(0)).isEqualTo(100L)
        assertThat(reward.getArgument<String>(3)).isEqualTo("signup:100")
    }

    @Test
    fun `동시 첫 로그인으로 create가 유니크 위반이면 재조회로 기존 User를 재사용한다`() {
        val concurrentlyCreated = User(id = 100L, nickname = "상대가만듦")
        // 1차 findExistingUser: 아직 못 찾음(둘 다 lookup miss). 2차(재시도): 상대가 커밋한 계정을 찾음.
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant()))
            .thenReturn(null)
            .thenReturn(concurrentlyCreated)
        // create는 유니크 위반으로 실패한다.
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant()))
            .thenThrow(DataIntegrityViolationException("uq_social_accounts_provider_user"))

        serviceWith(fakeVerifier("sub")).login("dummy")

        // 재조회로 찾은 기존 User로 토큰을 발급한다(500이 아님).
        verify(registrar, times(2)).findExistingUser(anySocialUserInfo(), anyInstant())
        verify(authTokenService).issueTokens(concurrentlyCreated)
    }

    @Test
    fun `create가 유니크 위반인데 재조회로도 못 찾으면 원 예외를 던진다`() {
        // 둘 다 못 찾고, create는 계속 실패하는 비정상 상태(데이터 정합성 문제). 삼키지 않고 드러낸다.
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant()))
            .thenThrow(DataIntegrityViolationException("boom"))

        assertThatThrownBy { serviceWith(fakeVerifier("sub")).login("dummy") }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        verifyNoInteractions(authTokenService)
    }

    @Test
    fun `검증 실패면 401을 전파하고 저장 부작용이 없다`() {
        val verifier = GoogleIdTokenVerifier {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 Google ID 토큰입니다.")
        }

        assertThatThrownBy { serviceWith(verifier).login("bad-token") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")

        verifyNoInteractions(registrar, authTokenService)
    }
    private companion object {
        const val IN_APP_DEVICE = "device-in-app"
        const val EXTERNAL_DEVICE = "device-external-browser"
    }

}
