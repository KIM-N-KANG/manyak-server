package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.AuthTokenService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

/**
 * GoogleLoginService의 find-or-create 계약을 고정한다.
 *
 * Google 호출은 가짜 [GoogleIdTokenVerifier]로 대체하고(외부 IO 없음),
 * 영속성([UserRepository]/[SocialAccountRepository])은 mock으로 검증한다.
 * 토큰 발급은 실제 [AuthTokenService]를 mock으로 두고 호출 여부만 본다.
 *
 * - 신규: SocialAccount가 없으면 User+SocialAccount를 생성하고 토큰을 발급한다.
 * - 기존: SocialAccount가 있으면 User를 재사용하고 lastLoginAt을 갱신한다.
 * - 검증 실패: verifier가 401을 던지면 그대로 전파한다(저장 부작용 없음).
 */
class GoogleLoginServiceTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val socialAccountRepository: SocialAccountRepository = mock(SocialAccountRepository::class.java)
    private val authTokenService: AuthTokenService = mock(AuthTokenService::class.java)

    private fun serviceWith(verifier: GoogleIdTokenVerifier): GoogleLoginService =
        GoogleLoginService(verifier, userRepository, socialAccountRepository, authTokenService)

    private fun fakeVerifier(info: SocialUserInfo): GoogleIdTokenVerifier =
        GoogleIdTokenVerifier { info }

    @Test
    fun `신규 사용자는 User와 SocialAccount를 생성하고 토큰을 발급한다`() {
        val verifier = fakeVerifier(
            SocialUserInfo(
                providerUserId = "google-sub-123",
                email = "alice@example.com",
                name = "Alice",
                picture = "https://example.com/alice.png",
            ),
        )
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-sub-123"))
            .thenReturn(null)
        // save가 id가 채워진 User를 반환하도록(IDENTITY 시뮬레이션) 입력을 그대로 돌려준다.
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }

        serviceWith(verifier).login("dummy-id-token")

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        val savedUser = userCaptor.value
        assertThat(savedUser.nickname).isEqualTo("Alice")
        assertThat(savedUser.profileImageUrl).isEqualTo("https://example.com/alice.png")
        assertThat(savedUser.status).isEqualTo(UserStatus.ACTIVE)

        val socialCaptor = ArgumentCaptor.forClass(SocialAccount::class.java)
        verify(socialAccountRepository).save(socialCaptor.capture())
        val savedSocial = socialCaptor.value
        assertThat(savedSocial.provider).isEqualTo(SocialProvider.GOOGLE)
        assertThat(savedSocial.providerUserId).isEqualTo("google-sub-123")
        assertThat(savedSocial.email).isEqualTo("alice@example.com")
        assertThat(savedSocial.lastLoginAt).isNotNull()

        verify(authTokenService).issueTokens(savedUser)
    }

    @Test
    fun `이름이 없으면 이메일 local-part를 닉네임으로 쓴다`() {
        val verifier = fakeVerifier(
            SocialUserInfo(providerUserId = "sub", email = "bob@example.com", name = null, picture = null),
        )
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub")).thenReturn(null)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }

        serviceWith(verifier).login("dummy")

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).isEqualTo("bob")
    }

    @Test
    fun `이름과 이메일이 모두 없으면 기본 닉네임을 쓴다`() {
        val verifier = fakeVerifier(
            SocialUserInfo(providerUserId = "sub", email = null, name = null, picture = null),
        )
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub")).thenReturn(null)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }

        serviceWith(verifier).login("dummy")

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).isEqualTo("사용자")
    }

    @Test
    fun `기존 사용자는 재사용하고 lastLoginAt을 갱신하며 새 User를 만들지 않는다`() {
        val verifier = fakeVerifier(
            SocialUserInfo(providerUserId = "google-sub-123", email = "alice@example.com", name = "Alice", picture = null),
        )
        val existingUser = User(id = 42L, nickname = "기존닉")
        val before = Instant.now().minusSeconds(3600)
        val existingSocial = SocialAccount(
            id = 7L,
            userId = 42L,
            provider = SocialProvider.GOOGLE,
            providerUserId = "google-sub-123",
            email = "alice@example.com",
            lastLoginAt = before,
        )
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-sub-123"))
            .thenReturn(existingSocial)
        `when`(userRepository.findById(42L)).thenReturn(Optional.of(existingUser))

        serviceWith(verifier).login("dummy")

        // 신규 생성 경로를 타지 않는다.
        verify(userRepository, never()).save(any(User::class.java))
        // lastLoginAt이 더 최근으로 갱신된다.
        assertThat(existingSocial.lastLoginAt).isAfter(before)
        verify(authTokenService).issueTokens(existingUser)
    }

    @Test
    fun `기존 SocialAccount가 가리키는 User가 사라졌으면 401이다`() {
        val verifier = fakeVerifier(
            SocialUserInfo(providerUserId = "sub", email = null, name = null, picture = null),
        )
        val social = SocialAccount(userId = 99L, provider = SocialProvider.GOOGLE, providerUserId = "sub")
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub")).thenReturn(social)
        `when`(userRepository.findById(99L)).thenReturn(Optional.empty())

        assertThatThrownBy { serviceWith(verifier).login("dummy") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
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

        // 검증이 먼저 실패하므로 저장소·토큰 발급은 전혀 호출되지 않는다.
        verifyNoInteractions(userRepository, socialAccountRepository, authTokenService)
    }
}
