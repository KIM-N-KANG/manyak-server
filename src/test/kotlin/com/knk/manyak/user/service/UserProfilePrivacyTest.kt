package com.knk.manyak.user.service

import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.social.ProfileImagePresetService
import com.knk.manyak.global.error.CodedResponseStatusException
import com.knk.manyak.user.dto.UpdateProfileRequest
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.server.ResponseStatusException

class UserProfilePrivacyTest {
    private val updater = mock(ProfileUpdater::class.java)
    private val users = mock(UserRepository::class.java)
    private val service = UserProfileService(updater, users, mock(ProfileImagePresetService::class.java))
    private val request = UpdateProfileRequest(nickname = "PrivateNickname")

    @Test
    fun `재조회로 닉네임 중복을 확정한 경우에만 원인 없는 409로 바꾼다`() {
        `when`(updater.update(1L, request)).thenThrow(DataIntegrityViolationException("PrivateNickname"))
        `when`(users.existsByNicknameKeyExcludingSelf("privatenickname", 1L)).thenReturn(true)
        assertThatThrownBy { service.updateProfile(1L, request) }
            .isInstanceOf(CodedResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageNotContaining("PrivateNickname")
            .extracting("errorCode").isEqualTo("NICKNAME_TAKEN")
        verify(users).existsByNicknameKeyExcludingSelf("privatenickname", 1L)
    }

    @Test
    fun `닉네임 충돌이 아닌 무결성 오류를 409로 오분류하지 않는다`() {
        `when`(updater.update(1L, request)).thenThrow(DataIntegrityViolationException("PrivateNickname"))
        assertThatThrownBy { service.updateProfile(1L, request) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasNoCause()
            .hasMessageContaining("500")
            .hasMessageNotContaining("PrivateNickname")
    }
}
