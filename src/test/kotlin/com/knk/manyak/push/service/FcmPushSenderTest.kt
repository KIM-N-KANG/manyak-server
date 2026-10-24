package com.knk.manyak.push.service

import com.google.firebase.messaging.AndroidConfig
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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * FCM 발송기 단위 검증(KNK-1130). 실제 FCM은 부르지 않고 [FirebaseMessaging]을 목으로 둔다.
 * - 서비스 계정 미설정(messaging=null)이면 no-op이다. 토큰 조회조차 하지 않는다.
 * - 정지·탈퇴 회원에게는 보내지 않는다(등록 뒤에 상태가 바뀐 경우).
 * - 무효 토큰 정리가 DB 오류로 실패해도 남은 기기 발송은 이어진다.
 * - 회원의 등록 기기 전부에 한 건씩 보낸다.
 * - UNREGISTERED 응답은 그 토큰만 지우고 나머지 발송은 계속한다. 다른 오류는 토큰을 남긴다.
 * - 결과는 outcome 태그 카운터에 쌓인다.
 * - 모든 발송 데이터에 수신 회원의 public_id를 `recipientId`로 덧붙인다(KNK-1220). 앱이 기기 공유 시 남의 알림을 거른다.
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

    private val publicId: UUID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")

    private fun stubUser(status: UserStatus) {
        `when`(userRepository.findById(7L))
            .thenReturn(Optional.of(User(id = 7L, publicId = publicId, nickname = "발송대상", status = status)))
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
    fun `발송 데이터에 수신 회원의 publicId를 recipientId로 덧붙인다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "tok-a")))
        `when`(messaging.send(any())).thenReturn("projects/x/messages/1")
        val captor = ArgumentCaptor.forClass(Message::class.java)

        // 시나리오가 같은 키를 넘겨도 모듈 값이 이긴다 — 수신자 판정 키를 호출자가 덮어쓰지 못한다.
        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED", "storyId" to "abc", "recipientId" to "spoofed"))

        verify(messaging).send(captor.capture())
        val data = dataOf(captor.value)
        assertThat(data).containsEntry("recipientId", publicId.toString())
        assertThat(data).containsEntry("type", "STORY_COMPLETED").containsEntry("storyId", "abc")
    }

    /** [Message]는 data 접근자가 없어 SDK 내부 필드를 읽는다. 키 이름이 바뀌면 이 헬퍼만 고친다. */
    @Suppress("UNCHECKED_CAST")
    private fun dataOf(message: Message): Map<String, String> =
        Message::class.java.getDeclaredField("data").also { it.isAccessible = true }.get(message) as Map<String, String>

    // SDK가 공개 접근자를 제공하지 않아 기존 data 검증과 같은 방식으로 전송 객체를 확인한다.
    private fun fieldOf(value: Any, name: String): Any? =
        value.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(value)

    @Test
    fun `ANDROID는 HIGH data 전용이고 webpush가 없다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "androidConfig")))
        sender.sendToUser(7L, mapOf("title" to "제목"))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        assertThat(fieldOf(captor.value, "webpushConfig")).isNull()
        assertThat(fieldOf(captor.value, "notification")).isNull()
        assertThat(fieldOf(fieldOf(captor.value, "androidConfig")!!, "priority")).isEqualTo("high")
        assertThat(fieldOf(fieldOf(captor.value, "androidConfig")!!, "ttl")).isNull()
    }

    @Test
    fun `ANDROID는 지정한 NORMAL 우선순위와 TTL을 전달한다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "android")))

        sender.sendToUser(7L, mapOf("type" to "ATTENDANCE_REMINDER"), AndroidConfig.Priority.NORMAL, 500L)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val android = fieldOf(captor.value, "androidConfig")!!
        assertThat(fieldOf(android, "priority")).isEqualTo("normal")
        assertThat(fieldOf(android, "ttl")).isEqualTo("0.500000000s")
    }

    @Test
    fun `기기마다 남은 TTL을 다시 계산하고 자정부터 나머지 기기를 중단한다`() {
        val midnight = Instant.parse("2026-10-17T15:00:00Z")
        val clock = mock(Clock::class.java)
        `when`(clock.instant()).thenReturn(midnight.minusSeconds(2), midnight.minusMillis(500), midnight)
        val sender = FcmPushSender(messaging, repository, userRepository, registry, clock = clock)
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(
            listOf(token(1, "a"), token(2, "b"), token(3, "c"), token(4, "d")),
        )

        sender.sendToUser(7L, mapOf("type" to "ATTENDANCE_REMINDER"), AndroidConfig.Priority.NORMAL, expiresAt = midnight)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging, times(2)).send(captor.capture())
        assertThat(captor.allValues.map { fieldOf(fieldOf(it, "androidConfig")!!, "ttl") })
            .containsExactly("2s", "0.500000000s")
    }

    @Test
    fun `이미 만료된 WEB 기기에는 발송하지 않는다`() {
        val expiry = Instant.parse("2026-10-17T15:00:00Z")
        val sender = FcmPushSender(messaging, repository, userRepository, registry, clock = Clock.fixed(expiry, ZoneOffset.UTC))
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(
            listOf(token(1, "web").apply { platform = PushPlatform.WEB }),
        )

        sender.sendToUser(7L, mapOf("type" to "ATTENDANCE_REMINDER"), expiresAt = expiry)

        verify(messaging, never()).send(any())
    }

    @Test
    fun `프로모션의 NORMAL 우선순위는 TTL을 설정하지 않는다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "android")))

        sender.sendToUser(7L, mapOf("type" to "PROMOTION"), AndroidConfig.Priority.NORMAL)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val android = fieldOf(captor.value, "androidConfig")!!
        assertThat(fieldOf(android, "priority")).isEqualTo("normal")
        assertThat(fieldOf(android, "ttl")).isNull()
    }

    @Test
    fun `WEB은 광고 문구와 수신자 및 딥링크를 그대로 보낸다`() {
        val web = token(1, "web").apply { platform = PushPlatform.valueOf("WEB") }
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(web))
        val data = mapOf("title" to "(광고) 선물", "body" to "출석하세요", "deepLink" to "https://manyak.app/shop")
        sender.sendToUser(7L, data)
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val message = captor.value
        assertThat(dataOf(message)).containsAllEntriesOf(data).containsEntry("recipientId", publicId.toString())
        assertThat(fieldOf(message, "androidConfig")).isNull()
        val webpush = fieldOf(message, "webpushConfig")!!
        val notification = fieldOf(webpush, "notification") as Map<*, *>
        assertThat(notification["title"]).isEqualTo("(광고) 선물")
        assertThat(notification["body"]).isEqualTo("출석하세요")
        assertThat(notification["icon"]).isEqualTo("https://manyak.app/icons/icon-192.png")
        assertThat(fieldOf(fieldOf(webpush, "fcmOptions")!!, "link")).isEqualTo(data["deepLink"])
    }

    @Test
    fun `WEB 스토리 완성은 고정 제목과 스토리 본문을 표시하고 원본 데이터를 유지한다`() {
        val web = token(1, "web").apply { platform = PushPlatform.valueOf("WEB") }
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(web))
        sender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED", "title" to "스토리"))
        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val webpush = fieldOf(captor.value, "webpushConfig")!!
        assertThat(fieldOf(fieldOf(webpush, "fcmOptions")!!, "link")).isEqualTo("https://manyak.app")
        val notification = fieldOf(webpush, "notification") as Map<*, *>
        assertThat(notification["title"]).isEqualTo("스토리가 완성됐어요")
        assertThat(notification["body"]).isEqualTo("스토리")
        assertThat(dataOf(captor.value)).containsEntry("type", "STORY_COMPLETED").containsEntry("title", "스토리")
        assertThat(dataOf(captor.value)).doesNotContainKey("body")
    }

    @Test
    fun `WEB 검수 완료는 데이터의 제목과 본문을 그대로 표시한다`() {
        val web = token(1, "web").apply { platform = PushPlatform.WEB }
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(web))
        val data = mapOf(
            "type" to "STORY_MODERATION_COMPLETED", "title" to "검수를 통과했어요",
            "body" to "「스토리」의 등록이 완료됐어요. 지금 확인해 보세요.", "deepLink" to "https://manyak.app/studio?utm_source=push&utm_medium=push&utm_campaign=story_moderation_completed&utm_content=story_moderation_completed",
        )

        sender.sendToUser(7L, data)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        val webpush = fieldOf(captor.value, "webpushConfig")!!
        val notification = fieldOf(webpush, "notification") as Map<*, *>
        assertThat(notification["title"]).isEqualTo(data["title"])
        assertThat(notification["body"]).isEqualTo(data["body"])
        assertThat(fieldOf(fieldOf(webpush, "fcmOptions")!!, "link")).isEqualTo(data["deepLink"])
        assertThat(dataOf(captor.value)).containsAllEntriesOf(data)
    }

    @Test
    fun `ANDROID 출석의 KST 자정까지 TTL을 유지한다`() {
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(token(1, "android")))
        val now = java.time.ZonedDateTime.parse("2026-10-17T09:00:00+09:00[Asia/Seoul]")
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val ttlMillis = java.time.Duration.between(now, midnight).toMillis()

        sender.sendToUser(7L, mapOf("type" to "ATTENDANCE_REMINDER"), AndroidConfig.Priority.NORMAL, ttlMillis)

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging).send(captor.capture())
        assertThat(fieldOf(fieldOf(captor.value, "androidConfig")!!, "ttl")).isEqualTo("54000s")
    }

    @Test
    fun `WEB은 설정한 기본 주소와 아이콘을 사용하고 딥링크가 있으면 우선한다`() {
        val configuredSender = FcmPushSender(
            messaging, repository, userRepository, registry,
            webIconUrl = "https://web.example/icon.png", webBaseUrl = "https://web.example",
        )
        val web = token(1, "web").apply { platform = PushPlatform.WEB }
        `when`(repository.findTop10ByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(listOf(web))

        configuredSender.sendToUser(7L, mapOf("type" to "PROMOTION", "title" to "(광고) 선물", "body" to "내용"))
        configuredSender.sendToUser(7L, mapOf("type" to "STORY_COMPLETED", "title" to "스토리", "deepLink" to "https://web.example/stories/abc"))

        val captor = ArgumentCaptor.forClass(Message::class.java)
        verify(messaging, times(2)).send(captor.capture())
        val links = captor.allValues.map { message ->
            val webpush = fieldOf(message, "webpushConfig")!!
            assertThat((fieldOf(webpush, "notification") as Map<*, *>)["icon"]).isEqualTo("https://web.example/icon.png")
            fieldOf(fieldOf(webpush, "fcmOptions")!!, "link")
        }
        assertThat(links).containsExactly("https://web.example", "https://web.example/stories/abc")
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
