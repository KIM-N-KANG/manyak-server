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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

/**
 * SocialAccountRegistrar의 find-or-create 계약을 고정한다(영속성 책임만 가진다).
 *
 * 저장소는 mock으로 두고 호출·인자만 검증한다(JPA flush는 통합 테스트의 관심사).
 * provider는 인자로 받으므로 Google·Kakao 양쪽에 같은 계약이 적용되는지 함께 본다(스펙 §4-5 — 흐름은 provider 무관).
 *
 * - findExistingUser: 연동이 있으면 lastLoginAt 갱신 + User 반환, 없으면 null, User 부재면 401.
 * - createUserAndAccount: 닉네임을 [NicknameGenerator]로 발급해(소셜 클레임과 무관) User+SocialAccount를 생성한다.
 */
class SocialAccountRegistrarTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val socialAccountRepository: SocialAccountRepository = mock(SocialAccountRepository::class.java)

    // 닉네임 발급은 결정적 고정값으로 스텁해, 레지스트라가 생성기 결과를 그대로 쓰는지만 검증한다.
    private val nicknameGenerator = NicknameGenerator { GeneratedNickname(GENERATED_NICKNAME, GENERATED_NOUN) }
    private val profileImagePresetService: ProfileImagePresetService = mock(ProfileImagePresetService::class.java)
    private val registrar = SocialAccountRegistrar(
        userRepository, socialAccountRepository, nicknameGenerator, profileImagePresetService,
    )

    @Test
    fun `구글 연동이 있으면 lastLoginAt을 갱신하고 User를 반환한다`() {
        assertFindsExistingUser(SocialProvider.GOOGLE)
    }

    @Test
    fun `카카오 연동이 있으면 lastLoginAt을 갱신하고 User를 반환한다`() {
        assertFindsExistingUser(SocialProvider.KAKAO)
    }

    private fun assertFindsExistingUser(provider: SocialProvider) {
        val existingUser = User(id = 42L, nickname = "기존닉")
        val now = Instant.now()
        val before = now.minusSeconds(3600)
        val social = SocialAccount(
            id = 7L,
            userId = 42L,
            provider = provider,
            providerUserId = "social-sub-123",
            email = "alice@example.com",
            lastLoginAt = before,
        )
        `when`(socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(provider, "social-sub-123"))
            .thenReturn(social)
        // 갱신이 1행이면 그 연동은 아직 살아 있다(mock 기본값 0은 "그 사이 탈퇴됨"을 뜻해 null로 빠진다).
        `when`(socialAccountRepository.touchLastLoginAt(7L, now)).thenReturn(1)
        `when`(userRepository.findById(42L)).thenReturn(Optional.of(existingUser))

        val user = registrar.findExistingUser(provider, info("social-sub-123"), now)

        assertThat(user).isSameAs(existingUser)
        // 엔티티 대입이 아니라 조건부 단일 컬럼 갱신으로 기록한다(KNK-1053) — dirty checking UPDATE는 전 컬럼을 덮어
        // 동시에 커밋된 탈퇴의 tombstone·이메일 파기를 되돌린다.
        verify(socialAccountRepository).touchLastLoginAt(7L, now)
        assertThat(social.lastLoginAt).isEqualTo(before)
        verify(userRepository, never()).save(any(User::class.java))
    }

    @Test
    fun `findExistingUser는 provider가 다르면 같은 sub라도 찾지 않는다`() {
        // 계정 통합은 도입하지 않는다(스펙 §4-5 결정 기록) — 조회 키는 (provider, provider_user_id)다.
        `when`(socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.KAKAO, "shared-sub"))
            .thenReturn(null)

        assertThat(registrar.findExistingUser(SocialProvider.KAKAO, info("shared-sub"), Instant.now())).isNull()
        verify(socialAccountRepository).findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.KAKAO, "shared-sub")
    }

    @Test
    fun `findExistingUser는 조회 뒤 탈퇴가 커밋돼 갱신이 0행이면 null이다`() {
        // Codex 3차 리뷰 P2: 0행은 "그 시점에 살아 있는 연동이 없다"는 뜻이다. 여기서 DELETED User를 돌려주면
        // 로그인은 200인데 이후 모든 요청이 401인 좀비 세션이 된다. null로 빠져 바깥이 재가입 경로를 타야 한다.
        val social = SocialAccount(id = 11L, userId = 42L, provider = SocialProvider.GOOGLE, providerUserId = "raced-sub")
        val now = Instant.now()
        `when`(socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.GOOGLE, "raced-sub"))
            .thenReturn(social)
        `when`(socialAccountRepository.touchLastLoginAt(11L, now)).thenReturn(0)

        assertThat(registrar.findExistingUser(SocialProvider.GOOGLE, info("raced-sub"), now)).isNull()

        // User를 조회하지도 않는다(DELETED 계정을 꺼낼 이유가 없다).
        verify(userRepository, never()).findById(42L)
    }

    @Test
    fun `findExistingUser는 갱신 뒤 탈퇴가 커밋돼 계정이 DELETED면 null이다`() {
        // Codex 5차 리뷰 P2: 갱신이 1행이어도 그 다음 조회 사이에 탈퇴가 커밋될 수 있다(새 statement라 READ
        // COMMITTED에서 보인다). DELETED User를 돌려주면 로그인 200 → 이후 전부 401인 좀비 세션이 된다.
        val social = SocialAccount(id = 12L, userId = 55L, provider = SocialProvider.GOOGLE, providerUserId = "late-withdraw-sub")
        val deletedUser = User(id = 55L, nickname = "탈퇴한 사용자", status = UserStatus.DELETED)
        val now = Instant.now()
        `when`(socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.GOOGLE, "late-withdraw-sub"))
            .thenReturn(social)
        `when`(socialAccountRepository.touchLastLoginAt(12L, now)).thenReturn(1)
        `when`(userRepository.findById(55L)).thenReturn(Optional.of(deletedUser))

        // 401이 아니라 null이다 — 그 신원은 재가입으로 계속 쓸 수 있고, 바깥이 그 경로를 탄다.
        assertThat(registrar.findExistingUser(SocialProvider.GOOGLE, info("late-withdraw-sub"), now)).isNull()
    }

    @Test
    fun `findExistingUser는 연동이 없으면 null을 반환한다`() {
        `when`(socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.GOOGLE, "sub")).thenReturn(null)

        assertThat(registrar.findExistingUser(SocialProvider.GOOGLE, info("sub"), Instant.now())).isNull()
    }

    @Test
    fun `findExistingUser는 연동이 가리키는 User가 없으면 401이다`() {
        val social = SocialAccount(id = 3L, userId = 99L, provider = SocialProvider.GOOGLE, providerUserId = "sub")
        `when`(socialAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull(SocialProvider.GOOGLE, "sub")).thenReturn(social)
        val now = Instant.now()
        `when`(socialAccountRepository.touchLastLoginAt(3L, now)).thenReturn(1)
        `when`(userRepository.findById(99L)).thenReturn(Optional.empty())

        assertThatThrownBy { registrar.findExistingUser(SocialProvider.GOOGLE, info("sub"), now) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    @Test
    fun `구글 가입은 닉네임과 명사 매핑 프리셋 이미지로 User와 SocialAccount를 생성한다`() {
        assertCreatesUserAndAccount(SocialProvider.GOOGLE)
    }

    @Test
    fun `카카오 가입도 같은 규칙으로 User와 SocialAccount를 생성한다`() {
        assertCreatesUserAndAccount(SocialProvider.KAKAO)
    }

    private fun assertCreatesUserAndAccount(provider: SocialProvider) {
        // 순수 신규 가입: 그 소셜 신원의 소유자가 없다. mock 기본값이 0L이라 명시 스텁이 필요하다
        // (스텁이 없으면 "소유자 0번"으로 읽혀 재가입 분기를 탄다).
        doReturn(null).`when`(socialAccountRepository).findOwnerUserId(provider, "social-sub-123")
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        // 저장소 save는 저장된 엔티티를 돌려준다(실제 구현이 null을 주지 않는다). 반환값에 null을 두면 코틀린 null 검사에 걸린다.
        `when`(socialAccountRepository.save(any(SocialAccount::class.java))).thenAnswer { it.arguments[0] as SocialAccount }
        `when`(profileImagePresetService.imageUrlFor(GENERATED_NOUN)).thenReturn(PRESET_URL)
        `when`(profileImagePresetService.thumbnailBase64For(GENERATED_NOUN)).thenReturn(PRESET_THUMBNAIL)

        registrar.createUserAndAccount(
            provider,
            SocialUserInfo(
                providerUserId = "social-sub-123",
                email = "alice@example.com",
                name = "Alice",
                picture = "https://example.com/alice.png",
            ),
            Instant.now(),
        )

        val userCaptor = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(userCaptor.capture())
        // 소셜 `name`("Alice")이 아니라 생성기가 발급한 닉네임을 써야 한다(실명 노출 방지).
        assertThat(userCaptor.value.nickname).isEqualTo(GENERATED_NICKNAME)
        // 소셜 `picture`가 아니라 닉네임 명사에 매핑된 프리셋 URL·썸네일을 써야 한다(외부 사진 노출 방지, B7).
        assertThat(userCaptor.value.profileImageUrl).isEqualTo(PRESET_URL)
        assertThat(userCaptor.value.profileThumbnailBase64).isEqualTo(PRESET_THUMBNAIL)
        assertThat(userCaptor.value.status).isEqualTo(UserStatus.ACTIVE)
        // 초대 관계는 가입이 아니라 코드 입력(redeem)에서 저장된다(KNK-567). 생성 시점엔 비어 있어야 한다.
        assertThat(userCaptor.value.inviterUserId).isNull()
        // 순수 신규 가입은 재가입이 아니다(KNK-1053 — tombstone이 없으면 rejoined_at을 찍지 않는다).
        assertThat(userCaptor.value.rejoinedAt).isNull()

        val socialCaptor = ArgumentCaptor.forClass(SocialAccount::class.java)
        verify(socialAccountRepository).save(socialCaptor.capture())
        assertThat(socialCaptor.value.provider).isEqualTo(provider)
        assertThat(socialCaptor.value.providerUserId).isEqualTo("social-sub-123")
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
