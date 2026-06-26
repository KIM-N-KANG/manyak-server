package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.token.AuthTokenService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
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
 * - 신규 사용자: 없으면 createUserAndAccount로 만들고 토큰을 발급한다.
 * - 동시 첫 로그인: create가 유니크 위반(DataIntegrityViolationException)이면 재조회로 상대가 만든 계정을 재사용한다.
 * - 검증 실패: verifier가 401을 던지면 전파하고 저장 부작용이 없다.
 */
class GoogleLoginServiceTest {

    private val registrar: GoogleAccountRegistrar = mock(GoogleAccountRegistrar::class.java)
    private val authTokenService: AuthTokenService = mock(AuthTokenService::class.java)

    private fun serviceWith(verifier: GoogleIdTokenVerifier): GoogleLoginService =
        GoogleLoginService(verifier, registrar, authTokenService)

    private fun fakeVerifier(providerUserId: String = "sub"): GoogleIdTokenVerifier =
        GoogleIdTokenVerifier { SocialUserInfo(providerUserId = providerUserId) }

    // Kotlin non-null 파라미터에 Mockito any()를 쓰면 matcher가 null을 반환해 NPE가 난다.
    // 매처는 stubbing에서 값 자체가 쓰이지 않으므로, 타입만 맞춰주는 헬퍼로 우회한다(mockito-kotlin 미사용).
    private fun anySocialUserInfo(): SocialUserInfo = any(SocialUserInfo::class.java) ?: SocialUserInfo("x")
    private fun anyInstant(): Instant = any(Instant::class.java) ?: Instant.EPOCH

    @Test
    fun `기존 사용자면 재사용해 토큰을 발급하고 새로 만들지 않는다`() {
        val user = User(id = 42L, nickname = "기존닉")
        `when`(registrar.findExistingUser(anySocialUserInfo(), anyInstant())).thenReturn(user)

        serviceWith(fakeVerifier("sub")).login("dummy")

        verify(registrar, never()).createUserAndAccount(anySocialUserInfo(), anyInstant())
        verify(authTokenService).issueTokens(user)
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
}
