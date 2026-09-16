package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.chat.client.ChatCharacterImageEvent
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.image.service.UploadedImageStorage
import com.knk.manyak.image.service.UploadedObject
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class ChatRealtimeImageServiceTest {
    private val trials = mock(GuestTrialLimitService::class.java)
    private val policy = mock(CreditPolicyService::class.java)
    private val storage = mock(UploadedImageStorage::class.java)
    private val service = ChatRealtimeImageService(trials, policy, storage)
    private val chat = UUID.randomUUID()

    private fun reserve(enabled: Boolean, userId: Long?, deviceId: String?, chatId: UUID, turn: Int) =
        service.prepare(enabled, userId, deviceId).also { service.issue(it, chatId, turn) }

    private fun configureStorage() {
        `when`(trials.requireDeviceId("device")).thenReturn("device")
        `when`(storage.isEnabled()).thenReturn(true)
        `when`(storage.serveUrlOf(anyString())).thenAnswer { "https://cdn.test/${it.arguments[0]}" }
        `when`(storage.presignRealtimeImage(anyString(), (any(Duration::class.java) ?: Duration.ZERO))).thenReturn("https://s3.test/signed")
    }

    @Test fun `off는 체험과 저장소를 호출하지 않는다`() {
        assertNull(reserve(false, 1L, null, chat, 2).slot)
        verifyNoInteractions(trials, storage)
    }
    @Test fun `게스트 체험 소진은 슬롯 없이 진행한다`() {
        configureStorage()
        assertNull(reserve(true, null, "device", chat, 2).slot)
        verify(trials).reserve("device", GuestTrialLimitService.Counter.CHAT_IMAGE)
    }
    @Test fun `회원 체험 소진 후 비용을 판정하고 차감 뒤 슬롯을 발급한다`() {
        configureStorage()
        `when`(policy.amountOf(CreditPolicyKey.CHAT_IMAGE_COST)).thenReturn(0)
        assertNotNull(reserve(true, 1L, null, chat, 2).slot)
        `when`(policy.amountOf(CreditPolicyKey.CHAT_IMAGE_COST)).thenReturn(50)
        val paid = service.prepare(true, 1L, null)
        assertEquals(50, paid.cost)
        assertNull(paid.slot)
        service.issue(paid, chat, 2)
        assertNotNull(paid.slot)
    }
    @Test fun `체험은 예약하고 슬롯 키는 재생성마다 다르며 복원은 한번이다`() {
        configureStorage()
        `when`(trials.reserveMember(1L, GuestTrialLimitService.Counter.CHAT_IMAGE)).thenReturn(true)
        val first = reserve(true, 1L, null, chat, 2)
        val second = reserve(true, 1L, null, chat, 2)
        assertTrue(first.slot!!.key.startsWith("chat-images/$chat/2-"))
        assertNotEquals(first.slot!!.key, second.slot!!.key)
        service.restore(first)
        service.restore(first)
        verify(trials, times(1)).restoreMember(1L, GuestTrialLimitService.Counter.CHAT_IMAGE)
    }
    @Test fun `미발급 URL과 없는 객체는 본문과 목록에서 제거하고 부모는 유지한다`() {
        configureStorage()
        val reservation = reserve(true, 1L, null, chat, 2)
        val url = reservation.slot!!.publicUrl
        val wrong = "https://other.test/chat-images/wrong.webp"
        val parent = "https://cdn.test/characters/parent.webp"
        val result = ChatTurnAiResult("[[$url]]\n[[$wrong]]\n[[$parent]]\n본문", emptyList(),
            characterImages = listOf(ChatCharacterImageEvent("a", url), ChatCharacterImageEvent("b", wrong), ChatCharacterImageEvent("c", parent)))
        val checked = service.validate(reservation, result)
        assertFalse(checked.success)
        assertEquals("\n\n[[$parent]]\n본문", checked.result.aiOutput)
        assertEquals(listOf(ChatCharacterImageEvent("c", parent)), checked.result.characterImages)
        `when`(storage.head(reservation.slot!!.key)).thenReturn(UploadedObject("image/webp", 10))
        val success = service.validate(reservation, result)
        assertTrue(success.success)
        assertTrue(success.result.aiOutput.contains("[[$url]]"))
        assertFalse(success.result.aiOutput.contains(wrong))
    }
    @Test fun `목록에만 있는 발급 URL은 실패이고 목록에서 제거한다`() {
        configureStorage()
        val reservation = reserve(true, 1L, null, chat, 1)
        `when`(storage.head(anyString())).thenReturn(UploadedObject("image/webp", 10))
        val checked = service.validate(reservation, ChatTurnAiResult("본문", emptyList(),
            characterImages = listOf(ChatCharacterImageEvent("인물", reservation.slot!!.publicUrl))))
        assertFalse(checked.success)
        assertTrue(checked.result.characterImages.isEmpty())
        verify(storage, never()).head(anyString())
    }
    @Test fun `마커에만 있는 발급 URL은 HEAD 성공이면 성공이다`() {
        configureStorage()
        val reservation = reserve(true, 1L, null, chat, 1)
        `when`(storage.head(anyString())).thenReturn(UploadedObject("image/webp", 10))
        val output = "[[${reservation.slot!!.publicUrl}]]본문"
        val checked = service.validate(reservation, ChatTurnAiResult(output, emptyList()))
        assertTrue(checked.success)
        assertEquals(output, checked.result.aiOutput)
    }
    @Test fun `저장소 오류는 이미지를 탈락시킨다`() {
        configureStorage()
        val reservation = reserve(true, 1L, null, chat, 1)
        `when`(storage.head(anyString())).thenThrow(IllegalStateException("unavailable"))
        assertFalse(service.validate(reservation, ChatTurnAiResult("[[${reservation.slot!!.publicUrl}]]", emptyList())).success)
    }
}
