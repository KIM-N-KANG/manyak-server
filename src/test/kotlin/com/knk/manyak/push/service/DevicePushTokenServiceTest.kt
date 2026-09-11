package com.knk.manyak.push.service

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.server.ResponseStatusException

/**
 * 등록·삭제의 **잠금 계약**을 고정한다(KNK-1131, Codex 2차 리뷰 P1).
 *
 * 잠금 없이 상태를 읽으면 탈퇴가 사용자 행을 잠그고 토큰을 지운 뒤 커밋해도, 대기하던 등록이 DELETED 회원에게
 * 새 토큰을 남긴다. 진짜 경합은 스레드로 재현하지 않고 "잠금 조회를 쓰는가"와 "상태별 거부 코드"로 나눠 고정한다.
 */
class DevicePushTokenServiceTest {

    private val devicePushTokenRepository: DevicePushTokenRepository = mock(DevicePushTokenRepository::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val service = DevicePushTokenService(devicePushTokenRepository, userRepository)

    private fun lockedUser(id: Long, status: UserStatus): User =
        User(id = id, nickname = "푸시유저", status = status).also {
            `when`(userRepository.findByIdForUpdate(id)).thenReturn(it)
        }

    @Test
    fun `등록은 사용자 행을 잠그고 읽는다`() {
        lockedUser(1L, UserStatus.ACTIVE)
        `when`(devicePushTokenRepository.findByTokenForUpdate(TOKEN)).thenReturn(null)
        `when`(devicePushTokenRepository.findAllByUserIdOrderByUpdatedAtAsc(1L)).thenReturn(emptyList())

        service.register(1L, TOKEN, PushPlatform.ANDROID)

        // 잠금 없는 findById로 읽으면 탈퇴 커밋 경합에서 DELETED 회원에게 토큰이 남는다.
        verify(userRepository).findByIdForUpdate(1L)
        verify(userRepository, never()).findById(1L)
    }

    @Test
    fun `삭제도 사용자 행을 잠그고 읽는다`() {
        lockedUser(2L, UserStatus.ACTIVE)

        service.unregister(2L, TOKEN)

        verify(userRepository).findByIdForUpdate(2L)
        verify(userRepository, never()).findById(2L)
        verify(devicePushTokenRepository).deleteByUserIdAndToken(2L, TOKEN)
    }

    @Test
    fun `잠금 후 DELETED면 401이고 토큰을 남기지 않는다`() {
        lockedUser(3L, UserStatus.DELETED)

        assertThatThrownBy { service.register(3L, TOKEN, PushPlatform.ANDROID) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")

        verify(devicePushTokenRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `잠금 후 SUSPENDED면 403이고 지우지도 않는다`() {
        lockedUser(4L, UserStatus.SUSPENDED)

        assertThatThrownBy { service.unregister(4L, TOKEN) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("403 FORBIDDEN")

        verify(devicePushTokenRepository, never()).deleteByUserIdAndToken(anyLong(), anyString())
    }

    @Test
    fun `축출은 소유자 조건부 삭제를 부른다`() {
        // id만으로 지우면 후보를 뽑은 뒤 다른 회원이 소유권을 가져간 행까지 지운다.
        lockedUser(6L, UserStatus.ACTIVE)
        `when`(devicePushTokenRepository.findByTokenForUpdate(TOKEN)).thenReturn(null)
        val owned = (1L..DevicePushTokenService.MAX_DEVICES_PER_USER).map {
            DevicePushToken(id = it, userId = 6L, token = "tok-$it", platform = PushPlatform.ANDROID)
        }
        `when`(devicePushTokenRepository.findAllByUserIdOrderByUpdatedAtAsc(6L)).thenReturn(owned)

        service.register(6L, TOKEN, PushPlatform.ANDROID)

        // 가장 오래된 하나만, 소유자 조건과 함께 지운다.
        verify(devicePushTokenRepository).deleteByUserIdAndIdIn(6L, listOf(1L))
        verify(devicePushTokenRepository, never()).deleteAll(org.mockito.ArgumentMatchers.anyList())
    }

    @Test
    fun `사용자 행이 없으면 401이다`() {
        `when`(userRepository.findByIdForUpdate(5L)).thenReturn(null)

        assertThatThrownBy { service.register(5L, TOKEN, PushPlatform.ANDROID) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .hasToString("401 UNAUTHORIZED")
    }

    companion object {
        private const val TOKEN = "dEv1cE:APA91bFakeToken-0123456789_abcdefghijklmnopqrstuvwxyz"
    }
}
