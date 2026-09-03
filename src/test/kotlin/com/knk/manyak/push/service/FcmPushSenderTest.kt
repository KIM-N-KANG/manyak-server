package com.knk.manyak.push.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.push.entity.DevicePushToken
import com.knk.manyak.push.entity.PushPlatform
import com.knk.manyak.push.repository.DevicePushTokenRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional

/**
 * FCM 발송기 단위 검증(KNK-1130). 실제 FCM은 부르지 않고 [FirebaseMessaging]을 목으로 둔다.
 * - 서비스 계정 미설정(messaging=null)이면 no-op이다. 토큰 조회조차 하지 않는다.
 * - 정지·탈퇴 회원에게는 보내지 않는다(등록 뒤에 상태가 바뀐 경우).
 * - 무효 토큰 정리가 DB 오류로 실패해도 남은 기기 발송은 이어진다.
 * - 회원의 등록 기기 전부에 한 건씩 보낸다.
 * - UNREGISTERED 응답은 그 토큰만 지우고 나머지 발송은 계속한다. 다른 오류는 토큰을 남긴다.
 * - 결과는 outcome 태그 카운터에 쌓인다.
 */
class FcmPushSenderTest {

    private val messaging: FirebaseMessaging = mock(FirebaseMessaging::class.java)
    private val repository: DevicePushTokenRepository = mock(DevicePushTokenRepository::class.java)
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val registry = SimpleMeterRegistry()
    private val sender = FcmPushSender(messaging, repository, userRepository, registry)

    @BeforeEach
    fun setUp() {
        stubUser(UserStatus.ACTIVE)
    }

    private fun stubUser(status: UserStatus) {
        `when`(userRepository.findById(7L))
            .thenReturn(Optional.of(User(id = 7L, nickname = "발송대상", status = status)))
    }

    private fun token(id: Long, value: String) = DevicePushToken(id = id, userId = 7L, token = value, platform = PushPlatform.ANDROID)

    private fun count(outcome: String): Double =
        registry.find(FcmPushSender.METRIC_PUSH_SEND_RESULT).tag("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `서비스 계정이 없으면 아무것도 하지 않는다`() {
        val disabled = FcmPushSender(null, repository, userRepository, registry)

        disabled.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        verify(repository, never()).findTop10ByUserIdOrderByUpdatedAtDesc(7L)
        verify(messaging, never()).send(any())
    }

    @Test
    fun `등록된 기기마다 한 건씩 보낸다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "tok-a"), token(2, "tok-b")))
        `when`(messaging.send(any())).thenReturn("projects/x/messages/1")

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED", "storyId" to "abc"))

        verify(messaging, times(2)).send(any(Message::class.java))
        assertThat(count(FcmPushSender.OUTCOME_SUCCESS)).isEqualTo(2.0)
        verify(repository, never()).deleteById(any())
    }

    @Test
    fun `토큰이 없으면 발송하지 않는다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(emptyList())

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        verify(messaging, never()).send(any())
    }

    @Test
    fun `UNREGISTERED면 그 토큰만 지우고 나머지는 계속 보낸다`() {
        val dead = token(1, "tok-dead")
        val alive = token(2, "tok-alive")
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(dead, alive))
        val unregistered = mock(FirebaseMessagingException::class.java)
        `when`(unregistered.messagingErrorCode).thenReturn(MessagingErrorCode.UNREGISTERED)
        // 첫 호출(dead)만 예외, 두 번째(alive)는 성공.
        `when`(messaging.send(any())).thenThrow(unregistered).thenReturn("projects/x/messages/2")

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        verify(repository).deleteById(1L)
        verify(repository, never()).deleteById(2L)
        verify(messaging, times(2)).send(any())
        assertThat(count(FcmPushSender.OUTCOME_UNREGISTERED)).isEqualTo(1.0)
        assertThat(count(FcmPushSender.OUTCOME_SUCCESS)).isEqualTo(1.0)
    }

    @Test
    fun `다른 FCM 오류는 토큰을 남기고 실패로 센다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "tok-a")))
        val internal = mock(FirebaseMessagingException::class.java)
        `when`(internal.messagingErrorCode).thenReturn(MessagingErrorCode.INTERNAL)
        `when`(messaging.send(any())).thenThrow(internal)

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        verify(repository, never()).deleteById(any())
        assertThat(count(FcmPushSender.OUTCOME_FAILURE)).isEqualTo(1.0)
    }

    @Test
    fun `SDK가 런타임 예외를 던져도 다음 기기로 넘어간다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "tok-a"), token(2, "tok-b")))
        `when`(messaging.send(any())).thenThrow(IllegalStateException("boom")).thenReturn("projects/x/messages/2")

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        verify(messaging, times(2)).send(any())
        assertThat(count(FcmPushSender.OUTCOME_FAILURE)).isEqualTo(1.0)
        assertThat(count(FcmPushSender.OUTCOME_SUCCESS)).isEqualTo(1.0)
    }

    @Test
    fun `정지 계정이면 보내지 않는다`() {
        stubUser(UserStatus.SUSPENDED)

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        // 토큰 조회조차 하지 않는다 — 상태 판정이 먼저다.
        verify(repository, never()).findTop10ByUserIdOrderByUpdatedAtDesc(7L)
        verify(messaging, never()).send(any())
    }

    @Test
    fun `무효 토큰 정리가 실패해도 다음 기기로 넘어간다`() {
        val dead = token(1, "tok-dead")
        val alive = token(2, "tok-alive")
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(dead, alive))
        val unregistered = mock(FirebaseMessagingException::class.java)
        `when`(unregistered.messagingErrorCode).thenReturn(MessagingErrorCode.UNREGISTERED)
        `when`(messaging.send(any())).thenThrow(unregistered).thenReturn("projects/x/messages/2")
        // 정리 실패는 형제 catch에 잡히지 않아, 가두지 않으면 sendToUser 밖으로 새고 뒤 기기가 발송되지 않는다.
        doThrow(DataIntegrityViolationException("db down")).`when`(repository).deleteById(1L)

        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED"))

        verify(messaging, times(2)).send(any())
        assertThat(count(FcmPushSender.OUTCOME_FAILURE)).isEqualTo(1.0)
        assertThat(count(FcmPushSender.OUTCOME_SUCCESS)).isEqualTo(1.0)
    }
}
