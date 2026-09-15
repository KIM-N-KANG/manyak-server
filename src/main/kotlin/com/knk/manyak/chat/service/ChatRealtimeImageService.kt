package com.knk.manyak.chat.service

import com.knk.manyak.chat.client.ChatImageSlot
import com.knk.manyak.chat.client.ChatTurnAiResult
import com.knk.manyak.credit.service.CreditPolicyKey
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.credit.service.GuestTrialLimitService
import com.knk.manyak.credit.service.GuestTrialLimitService.Counter
import com.knk.manyak.image.service.UploadedImageStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** 턴·재생성에서 같은 슬롯/체험/검증 규칙을 사용한다. 원장은 KNK-1292에서 연결한다. */
@Service
class ChatRealtimeImageService(
    private val trials: GuestTrialLimitService,
    private val policy: CreditPolicyService,
    private val storage: UploadedImageStorage,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    class Reservation(
        val slot: ChatImageSlot?,
        val userId: Long? = null,
        val deviceId: String? = null,
        val trialReserved: Boolean = false,
    ) {
        internal val restored = AtomicBoolean(false)
    }
    data class Validated(val result: ChatTurnAiResult, val success: Boolean)

    fun reserve(enabled: Boolean, userId: Long?, deviceId: String?, chatId: UUID, turnNumber: Int): Reservation {
        if (!enabled || !storage.isEnabled()) return Reservation(null)
        val reserved = if (userId != null) trials.reserveMember(userId, Counter.CHAT_IMAGE)
            else trials.reserve(trials.requireDeviceId(deviceId), Counter.CHAT_IMAGE)
        // KNK-1292: 양수 유료 이미지의 CHAT_IMAGE 차감·환불을 붙이기 전에는 슬롯을 발급하지 않는다.
        if (!reserved && (userId == null || policy.amountOf(CreditPolicyKey.CHAT_IMAGE_COST) > 0)) {
            return Reservation(null)
        }
        val reservation = Reservation(null, userId, deviceId, reserved)
        try {
            val key = "chat-images/$chatId/$turnNumber-${UUID.randomUUID()}.webp"
            val publicUrl = storage.serveUrlOf(key)
            val uploadUrl = storage.presignRealtimeImage(key, Duration.ofMinutes(10))
            if (publicUrl != null && uploadUrl != null) {
                return Reservation(ChatImageSlot(key, uploadUrl, publicUrl), userId, deviceId, reserved)
            }
        } catch (ex: Exception) {
            // 서명 URL은 자격 증명이므로 로그에 포함하지 않는다.
            logger.warn("실시간 이미지 슬롯 발급 실패: {}", ex.javaClass.simpleName)
        }
        restore(reservation)
        return Reservation(null)
    }

    /** DB 저장 트랜잭션 안에서 호출한다. 발급한 정확한 URL만 HEAD하며 임의 URL은 요청하지 않는다. */
    fun validate(reservation: Reservation, result: ChatTurnAiResult): Validated {
        val urls = markers.findAll(result.aiOutput).map { it.groupValues[1] }.toList() +
            result.characterImages.map { it.imageUrl }
        val slot = reservation.slot
        val accepted = slot != null && slot.publicUrl in urls && try {
            storage.head(slot.key) != null
        } catch (ex: Exception) {
            logger.warn("실시간 이미지 객체 확인 실패: {}", ex.javaClass.simpleName)
            false
        }
        fun invalid(url: String): Boolean = isRealtimeUrl(url) && !(accepted && url == slot?.publicUrl)
        return Validated(
            result.copy(
                aiOutput = markers.replace(result.aiOutput) { match ->
                    if (invalid(match.groupValues[1])) "" else match.value
                },
                characterImages = result.characterImages.filterNot { invalid(it.imageUrl) },
            ),
            accepted,
        )
    }

    /** 실패 종료·검증 탈락·스케줄 거부가 겹쳐도 이미지 예약만 한 번 복원한다. */
    fun restore(reservation: Reservation) {
        if (!reservation.trialReserved || !reservation.restored.compareAndSet(false, true)) return
        try {
            if (reservation.userId != null) trials.restoreMember(reservation.userId, Counter.CHAT_IMAGE)
            else trials.restore(requireNotNull(reservation.deviceId), Counter.CHAT_IMAGE)
        } catch (ex: Exception) {
            logger.warn("실시간 이미지 체험 복원 실패: {}", ex.javaClass.simpleName)
        }
    }

    private fun isRealtimeUrl(url: String): Boolean = try {
        URI(url).path.orEmpty().split('/').contains("chat-images")
    } catch (_: Exception) {
        url.contains("chat-images/")
    }

    private companion object {
        val markers = Regex("\\[\\[([^\\]\\r\\n]+)]]")
    }
}
