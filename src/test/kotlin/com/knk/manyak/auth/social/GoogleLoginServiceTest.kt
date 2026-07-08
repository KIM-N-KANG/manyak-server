package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.token.AuthTokenService
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.invite.service.InviteService
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
    private val inviteService: InviteService = mock(InviteService::class.java)
    private val guestTrialLimitService: GuestTrialLimitService = mock(GuestTrialLimitService::class.java)

    private val signupReward = 100L

    private fun serviceWith(verifier: GoogleIdTokenVerifier): GoogleLoginService =
        GoogleLoginService(
            verifier, registrar, authTokenService, creditWalletService, inviteService, guestTrialLimitService, signupReward,
        )

    private fun fakeVerifier(providerUserId: String = "sub"): GoogleIdTokenVerifier =
        GoogleIdTokenVerifier { SocialUserInfo(providerUserId = providerUserId) }

    // Kotlin non-null 파라미터에 Mockito any()를 쓰면 matcher가 null을 반환해 NPE가 난다.
    // 매처는 stubbing에서 값 자체가 쓰이지 않으므로, 타입만 맞춰주는 헬퍼로 우회한다(mockito-kotlin 미사용).
    private fun anySocialUserInfo(): SocialUserInfo = any(SocialUserInfo::class.java) ?: SocialUserInfo("x")
    private fun anyInstant(): Instant = any(Instant::class.java) ?: Instant.EPOCH

    // inviterUserId는 nullable(Long?)이라 null도 매칭해야 하므로 타입 없는 any()를 쓴다(any(Class)는 null 불일치).
    private fun anyInviterId(): Long? = any()

    @Test
    fun `기존 사용자면 재사용해 토큰을 발급하고 새로 만들지 않는다`() {
        val user = User(id = 42L, nickname = "기존닉")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy")

        verify(registrar, never()).createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId())
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
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId())).thenReturn(created)

        serviceWith(fakeVerifier("sub")).login("dummy")

        verify(registrar).createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId())
        verify(authTokenService).issueTokens(created)
    }

    @Test
    fun `신규 사용자면 signup 멱등 키로 가입 보상을 적립한다`() {
        val created = User(id = 7L, nickname = "신규")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId())).thenReturn(created)

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
    fun `신규 사용자면 초대자를 해석해 생성에 넘기고 영속 관계로 적립한다`() {
        // 초대자(5)를 코드로 해석해 생성 트랜잭션에 넘기고, 영속된 관계(inviterUserId=5)로 양쪽에 적립한다.
        val created = User(id = 7L, nickname = "신규", inviterUserId = 5L)
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(null)
        `when`(inviteService.resolveInviterId("INVITE123")).thenReturn(5L)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId())).thenReturn(created)

        serviceWith(fakeVerifier("sub")).login("dummy", "INVITE123")

        // 해석한 초대자 id를 생성에 넘긴다(생성 트랜잭션에 원자적으로 영속).
        val create = mockingDetails(registrar).invocations.single { it.method.name == "createUserAndAccount" }
        assertThat(create.getArgument<Long>(2)).isEqualTo(5L)
        // 영속 관계로 초대자(5)·피초대자(7) 양쪽에 적립을 위임한다.
        val reward = mockingDetails(inviteService).invocations.single { it.method.name == "rewardInvitePair" }
        assertThat(reward.getArgument<Long>(0)).isEqualTo(5L)
        assertThat(reward.getArgument<Long>(1)).isEqualTo(7L)
    }

    @Test
    fun `기존 사용자가 초대 관계 없이 로그인하면 초대 적립이 없다`() {
        val user = User(id = 42L, nickname = "기존닉") // inviterUserId=null
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy", "INVITE123")

        // 기존 사용자 경로는 코드를 해석하지 않고(관계 세팅 없음), 영속 관계도 없어 초대 적립을 부르지 않는다.
        // "이미 가입된 계정의 코드 제출은 무시"가 여기서 보장된다.
        verifyNoInteractions(inviteService)
    }

    @Test
    fun `영속된 초대자 관계가 있으면 코드 없이 재로그인해도 멱등 재적립한다`() {
        // 자가 복구: 계정에 초대자 관계(inviterUserId=5)가 남아 있으면 request에 코드가 없어도 매 로그인 재적립한다.
        val user = User(id = 42L, nickname = "피초대자", inviterUserId = 5L)
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy") // 코드 미제출

        // 기존 사용자 경로라 새로 만들지 않고(관계 재해석도 없이), 영속 관계만으로 초대자(5)·피초대자(42)에 적립한다.
        verify(registrar, never()).createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId())
        val reward = mockingDetails(inviteService).invocations.single { it.method.name == "rewardInvitePair" }
        assertThat(reward.getArgument<Long>(0)).isEqualTo(5L)
        assertThat(reward.getArgument<Long>(1)).isEqualTo(42L)
    }

    @Test
    fun `동시 첫 로그인 재조회로 재사용한 User에도 멱등 키로 보상을 적립한다`() {
        val concurrentlyCreated = User(id = 100L, nickname = "상대가만듦")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant()))
            .thenReturn(null)
            .thenReturn(concurrentlyCreated)
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId()))
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
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId()))
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
        `when`(registrar.createUserAndAccount(anySocialUserInfo(), anyInstant(), anyInviterId()))
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
}
