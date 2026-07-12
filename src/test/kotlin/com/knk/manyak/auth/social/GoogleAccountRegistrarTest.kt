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
 * - createUserAndAccount: 닉네임을 [NicknameGenerator]로 발급해(Google `name`과 무관) User+SocialAccount를 생성한다.
 */
class GoogleAccountRegistrarTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val socialAccountRepository: SocialAccountRepository = mock(SocialAccountRepository::class.java)

    // 닉네임 발급은 결정적 고정값으로 스텁해, 레지스트라가 생성기 결과를 그대로 쓰는지만 검증한다.
    private val nicknameGenerator = NicknameGenerator { GeneratedNickname(GENERATED_NICKNAME, GENERATED_NOUN) }
    private val profileImagePresetService: ProfileImagePresetService = mock(ProfileImagePresetService::class.java)
    private val registrar = GoogleAccountRegistrar(
        userRepository, socialAccountRepository, nicknameGenerator, profileImagePresetService,
    )

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
    fun `createUserAndAccount는 닉네임과 명사 매핑 프리셋 이미지로 User와 SocialAccount를 생성한다`() {
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(profileImagePresetService.imageUrlFor(GENERATED_NOUN)).thenReturn(PRESET_URL)
        `when`(profileImagePresetService.thumbnailBase64For(GENERATED_NOUN)).thenReturn(PRESET_THUMBNAIL)

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
        // Google `name`("Alice")이 아니라 생성기가 발급한 닉네임을 써야 한다(실명 노출 방지).
        assertThat(userCaptor.value.nickname).isEqualTo(GENERATED_NICKNAME)
        // Google `picture`가 아니라 닉네임 명사에 매핑된 프리셋 URL·썸네일을 써야 한다(외부 사진 노출 방지, B7).
        assertThat(userCaptor.value.profileImageUrl).isEqualTo(PRESET_URL)
        assertThat(userCaptor.value.profileThumbnailBase64).isEqualTo(PRESET_THUMBNAIL)
        assertThat(userCaptor.value.status).isEqualTo(UserStatus.ACTIVE)
        // 초대 관계는 가입이 아니라 코드 입력(redeem)에서 저장된다(KNK-567). 생성 시점엔 비어 있어야 한다.
        assertThat(userCaptor.value.inviterUserId).isNull()

        val socialCaptor = ArgumentCaptor.forClass(SocialAccount::class.java)
        verify(socialAccountRepository).save(socialCaptor.capture())
        assertThat(socialCaptor.value.provider).isEqualTo(SocialProvider.GOOGLE)
        assertThat(socialCaptor.value.providerUserId).isEqualTo("google-sub-123")
        assertThat(socialCaptor.value.email).isEqualTo("alice@example.com")
        assertThat(socialCaptor.value.lastLoginAt).isNotNull()
    }

    private fun info(providerUserId: String) =
        SocialUserInfo(providerUserId = providerUserId, email = null, name = null, picture = null)

    private companion object {
        const val GENERATED_NOUN = "이야기꾼"
        const val GENERATED_NICKNAME = "몽환적인 이야기꾼"
        const val PRESET_URL = "https://api.manyak.app/profile-presets/%EC%9D%B4%EC%95%BC%EA%B8%B0%EA%BE%BC.png"
        const val PRESET_THUMBNAIL = "iVBORw0KGgo="
    }
}
