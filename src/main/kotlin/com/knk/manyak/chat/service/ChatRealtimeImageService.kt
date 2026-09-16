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

/** 턴·재생성에서 같은 슬롯/체험/검증 규칙을 사용한다. 유료 항목은 호출부가 합산 차감한 뒤 슬롯을 발급한다. */
@Service
class ChatRealtimeImageService(
    private val trials: GuestTrialLimitService,
    private val policy: CreditPolicyService,
    private val storage: UploadedImageStorage,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    class Reservation(
        var slot: ChatImageSlot?,
        val userId: Long? = null,
        val deviceId: String? = null,
        val trialReserved: Boolean = false,
        val cost: Long = 0,
        val eligible: Boolean = false,
    ) {
        internal val restored = AtomicBoolean(false)
    }
    data class Validated(val result: ChatTurnAiResult, val success: Boolean)

    /** 체험·비용 판정만 한다. 비용을 실제 차감하기 전에는 presign하지 않는다. */
    fun prepare(enabled: Boolean, userId: Long?, deviceId: String?): Reservation {
        if (!enabled || !storage.isEnabled()) return Reservation(null)
        val reserved = if (userId != null) trials.reserveMember(userId, Counter.CHAT_IMAGE)
            else trials.reserve(trials.requireDeviceId(deviceId), Counter.CHAT_IMAGE)
        if (!reserved && userId == null) return Reservation(null)
        val cost = if (reserved) 0 else policy.amountOf(CreditPolicyKey.CHAT_IMAGE_COST)
        return Reservation(null, userId, deviceId, reserved, cost, eligible = true)
    }

    /** 합산 선차감 성공 뒤 호출한다. 발급 실패는 슬롯 없이 진행하며 완료 판정에서 이미지 비용만 환불한다. */
    fun issue(reservation: Reservation, chatId: UUID, turnNumber: Int) {
        if (!reservation.eligible) return
        try {
            val key = "chat-images/$chatId/$turnNumber-${UUID.randomUUID()}.webp"
            val publicUrl = storage.serveUrlOf(key)
            val uploadUrl = storage.presignRealtimeImage(key, Duration.ofMinutes(10))
            if (publicUrl != null && uploadUrl != null) {
                reservation.slot = ChatImageSlot(key, uploadUrl, publicUrl)
                return
            }
        } catch (ex: Exception) {
            logger.warn("실시간 이미지 슬롯 발급 실패: {}", ex.javaClass.simpleName)
        }
        restore(reservation)
    }

    /** DB 저장 트랜잭션 안에서 호출한다. 발급한 정확한 URL만 HEAD하며 임의 URL은 요청하지 않는다. */
    fun validate(reservation: Reservation, result: ChatTurnAiResult): Validated {
        // 저장 정본은 본문 마커다. 목록에만 있는 이미지는 기록에 남지 않으므로 성공이 아니다.
        val urls = markers.findAll(result.aiOutput).map { it.groupValues[1] }.toList()
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
