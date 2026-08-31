package com.knk.manyak.user.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.token.RefreshTokenStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException

/**
 * 회원 탈퇴의 **잠금 계약**을 고정한다(KNK-1053, Codex 4차 리뷰 P1).
 *
 * [User]에는 `@Version`도 `@DynamicUpdate`도 없어 dirty checking UPDATE가 전 컬럼을 덮는다. 사용자 행을 잠그지
 * 않으면 탈퇴가 읽은 뒤 커밋된 동시 갱신(`inviter_user_id` 등 승계 표식)이 통째로 되돌아간다.
 * 진짜 경합은 스레드로 재현하지 않고, "잠금 조회를 쓰는가"와 "재탈퇴를 막는가"로 나눠 고정한다.
 */
class UserWithdrawalServiceTest {

    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val socialAccountRepository: SocialAccountRepository = mock(SocialAccountRepository::class.java)
    private val refreshTokenStore: RefreshTokenStore = mock(RefreshTokenStore::class.java)
    private val service = UserWithdrawalService(userRepository, socialAccountRepository, refreshTokenStore)

    @Test
    fun `탈퇴는 사용자 행을 잠그고 읽는다`() {
        val user = User(id = 5L, nickname = "탈퇴예정자", status = UserStatus.ACTIVE)
        `when`(userRepository.findByIdForUpdate(5L)).thenReturn(user)
        `when`(socialAccountRepository.findByUserId(5L)).thenReturn(emptyList())

        service.withdraw(5L)

        // 잠금 없는 findById로 읽으면 동시 갱신이 되돌아간다 — 반드시 findByIdForUpdate여야 한다.
        verify(userRepository).findByIdForUpdate(5L)
        verify(userRepository, never()).findById(5L)
        assertThat(user.status).isEqualTo(UserStatus.DELETED)
        assertThat(user.withdrawnFromStatus).isEqualTo(UserStatus.ACTIVE)
    }

    @Test
    fun `정지 계정의 탈퇴는 허용하고 정지였다는 사실을 남긴다`() {
        // 앱 심사 요건상 정지 회원도 탈퇴할 수 있어야 한다(requireActiveStatus를 쓰면 403으로 막힌다).
        val user = User(id = 6L, nickname = "정지회원", status = UserStatus.SUSPENDED)
        `when`(userRepository.findByIdForUpdate(6L)).thenReturn(user)
        `when`(socialAccountRepository.findByUserId(6L)).thenReturn(emptyList())

        service.withdraw(6L)

        assertThat(user.status).isEqualTo(UserStatus.DELETED)
        assertThat(user.withdrawnFromStatus).isEqualTo(UserStatus.SUSPENDED)
    }

    @Test
    fun `잠금 후 이미 DELETED면 401이고 승계 근거를 덮어쓰지 않는다`() {
        // 필터 통과 직후 다른 요청의 탈퇴가 커밋된 경합. 여기서 막지 않으면 두 번째 탈퇴가
        // withdrawnFromStatus를 DELETED로 덮어 "정지였다"는 승계 근거를 지운다.
        val user = User(id = 7L, nickname = "탈퇴한 사용자", status = UserStatus.DELETED)
        user.withdrawnFromStatus = UserStatus.SUSPENDED
        `when`(userRepository.findByIdForUpdate(7L)).thenReturn(user)

        assertThatThrownBy { service.withdraw(7L) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")

        assertThat(user.withdrawnFromStatus).isEqualTo(UserStatus.SUSPENDED)
        verify(refreshTokenStore, never()).revokeAllForUser(7L)
    }
}
