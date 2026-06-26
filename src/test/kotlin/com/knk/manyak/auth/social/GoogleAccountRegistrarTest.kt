package com.knk.manyak.auth.social

import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

/**
 * GoogleAccountRegistrar의 find-or-create 계약을 고정한다(영속성 책임만 가진다).
 *
 * 저장소는 mock으로 두고 호출·인자만 검증한다(JPA flush는 통합 테스트의 관심사).
 *
 * - findExistingUser: 연동이 있으면 lastLoginAt 갱신 + User 반환, 없으면 null, User 부재면 401.
 * - createUserAndAccount: nickname을 50자로 정규화해 User+SocialAccount를 생성한다.
 */
class GoogleAccountRegistrarTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val socialAccountRepository: SocialAccountRepository = mock(SocialAccountRepository::class.java)
    private val registrar = GoogleAccountRegistrar(userRepository, socialAccountRepository)

    @Test
    fun `findExistingUser는 연동이 있으면 lastLoginAt을 갱신하고 User를 반환한다`() {
        val existingUser = User(id = 42L, nickname = "기존닉")
        val before = Instant.now().minusSeconds(3600)
        val social = SocialAccount(
            id = 7L,
            userId = 42L,
            provider = SocialProvider.GOOGLE,
            providerUserId = "google-sub-123",
            email = "alice@example.com",
            lastLoginAt = before,
        )
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "google-sub-123"))
            .thenReturn(social)
        `when`(userRepository.findById(42L)).thenReturn(Optional.of(existingUser))

        val user = registrar.findExistingUser(info("google-sub-123"), Instant.now())

        assertThat(user).isSameAs(existingUser)
        assertThat(social.lastLoginAt).isAfter(before)
        verify(userRepository, never()).save(any(User::class.java))
    }

    @Test
    fun `findExistingUser는 연동이 없으면 null을 반환한다`() {
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub")).thenReturn(null)

        assertThat(registrar.findExistingUser(info("sub"), Instant.now())).isNull()
    }

    @Test
    fun `findExistingUser는 연동이 가리키는 User가 없으면 401이다`() {
        val social = SocialAccount(userId = 99L, provider = SocialProvider.GOOGLE, providerUserId = "sub")
        `when`(socialAccountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub")).thenReturn(social)
        `when`(userRepository.findById(99L)).thenReturn(Optional.empty())

        assertThatThrownBy { registrar.findExistingUser(info("sub"), Instant.now()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `createUserAndAccount는 User와 SocialAccount를 생성한다`() {
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }

        registrar.createUserAndAccount(
            SocialUserInfo(
                providerUserId = "google-sub-123",
                email = "alice@example.com",
                name = "Alice",
                picture = "https://example.com/alice.png",
            ),
            Instant.now(),
        )

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).isEqualTo("Alice")
        assertThat(userCaptor.value.profileImageUrl).isEqualTo("https://example.com/alice.png")
        assertThat(userCaptor.value.status).isEqualTo(UserStatus.ACTIVE)

        val socialCaptor = ArgumentCaptor.forClass(SocialAccount::class.java)
        verify(socialAccountRepository).save(socialCaptor.capture())
        assertThat(socialCaptor.value.provider).isEqualTo(SocialProvider.GOOGLE)
        assertThat(socialCaptor.value.providerUserId).isEqualTo("google-sub-123")
        assertThat(socialCaptor.value.email).isEqualTo("alice@example.com")
        assertThat(socialCaptor.value.lastLoginAt).isNotNull()
    }

    @Test
    fun `이름이 없으면 이메일 local-part를 닉네임으로 쓴다`() {
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }

        registrar.createUserAndAccount(
            SocialUserInfo(providerUserId = "sub", email = "bob@example.com", name = null, picture = null),
            Instant.now(),
        )

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).isEqualTo("bob")
    }

    @Test
    fun `이름과 이메일이 모두 없으면 기본 닉네임을 쓴다`() {
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }

        registrar.createUserAndAccount(
            SocialUserInfo(providerUserId = "sub", email = null, name = null, picture = null),
            Instant.now(),
        )

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).isEqualTo("사용자")
    }

    @Test
    fun `50자를 넘는 이름은 50자로 잘라 저장한다`() {
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        val longName = "가".repeat(80)

        registrar.createUserAndAccount(
            SocialUserInfo(providerUserId = "sub", email = null, name = longName, picture = null),
            Instant.now(),
        )

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).hasSize(50)
        assertThat(userCaptor.value.nickname).isEqualTo("가".repeat(50))
    }

    @Test
    fun `이름이 없고 이메일 local-part가 50자를 넘으면 50자로 잘라 저장한다`() {
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        val longLocal = "a".repeat(80)

        registrar.createUserAndAccount(
            SocialUserInfo(providerUserId = "sub", email = "$longLocal@example.com", name = null, picture = null),
            Instant.now(),
        )

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        assertThat(userCaptor.value.nickname).isEqualTo("a".repeat(50))
    }

    private fun info(providerUserId: String) =
        SocialUserInfo(providerUserId = providerUserId, email = null, name = null, picture = null)
}
