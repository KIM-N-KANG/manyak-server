package com.knk.manyak.credit.service

import com.knk.manyak.credit.entity.CreditOrderProvider
import com.knk.manyak.credit.entity.CreditOrderStatus
import com.knk.manyak.credit.entity.CreditReason
import com.knk.manyak.credit.repository.CreditOrderRepository
import com.knk.manyak.credit.repository.CreditTransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.UUID

@Service
class GroblePaymentEventService(
    private val orders: CreditOrderRepository,
    private val transactions: CreditTransactionRepository,
    private val wallets: CreditWalletService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 모든 결제 경로에서 주문 → 지갑 → 로트 순으로 잠근다. 주문 전이와 원장·잔액 갱신은 함께 커밋한다. */
    @Transactional
    fun process(event: JsonNode, deliveryKey: String?): String {
        val type = event.path("type").stringValue(null)
        logger.debug("그로블 웹훅 수신: type={}, deliveryKey={}", type, deliveryKey?.take(255))
        val obj = event.path("data").path("object")
        return when (type) {
            "payment.completed" -> complete(event, obj)
            "payment.refunded" -> refund(obj)
            else -> {
                logger.debug("그로블 웹훅 미지원 이벤트 무시: type={}", type)
                "ignored"
            }
        }
    }

    private fun complete(event: JsonNode, obj: JsonNode): String {
        val reference = obj.path("sellerReference").stringValue(null) ?: return ignored("sellerReference 없음")
        val publicId = try { UUID.fromString(reference) } catch (_: IllegalArgumentException) {
            return ignored("sellerReference UUID 오류")
        }
        val order = orders.findByPublicIdForUpdate(publicId) ?: return ignored("주문 미매칭")
        if (order.provider != CreditOrderProvider.GROBLE || order.status != CreditOrderStatus.PENDING) return "ignored"
        val price = obj.path("pricing").path("finalAmount")
        if (!price.isIntegralNumber || !price.canConvertToLong() || price.longValue() != order.priceKrw) {
            return ignored("주문 결제 금액 불일치")
        }
        val merchantUid = obj.path("merchantUid").stringValue(null)?.takeIf { it.isNotBlank() && it.length <= 255 }
            ?: return ignored("merchantUid 오류")
        val eventId = event.path("id").stringValue(null)?.takeIf { it.isNotBlank() && it.length <= 248 }
            ?: return ignored("이벤트 id 오류")
        val key = "groble:$eventId"
        wallets.reward(order.userId, order.creditAmount, CreditReason.PURCHASE, key, "CREDIT_ORDER", order.id)
        val transaction = transactions.findByIdempotencyKey(key) ?: error("구매 적립 원장이 없습니다: orderId=${order.id}")
        // 같은 키의 적립이 이미 존재하면 상태를 복구하되 다른 주문의 키를 재사용하지 않는다.
        if (transaction.userId != order.userId || transaction.reason != CreditReason.PURCHASE ||
            transaction.amount != order.creditAmount || transaction.refType != "CREDIT_ORDER" || transaction.refId != order.id) {
            return ignored("다른 주문의 이벤트 id 중복")
        }
        order.status = CreditOrderStatus.COMPLETED
        order.completedAt = clock.instant()
        order.providerRef = merchantUid
        order.creditTransactionId = transaction.id
        return "completed"
    }

    private fun refund(obj: JsonNode): String {
        val merchantUid = obj.path("merchantUid").stringValue(null) ?: return ignored("환불 merchantUid 없음")
        val order = orders.findByProviderRefForUpdate(merchantUid) ?: return ignored("환불 주문 미매칭")
        val partial = obj.path("refund").path("partialRefund")
        if (!partial.isBoolean) return ignored("환불 partialRefund 오류")
        if (partial.booleanValue()) return ignored("부분 환불 미지원")
        if (order.provider != CreditOrderProvider.GROBLE || order.status != CreditOrderStatus.COMPLETED) return "ignored"
        wallets.reverseLot(order.userId, checkNotNull(order.creditTransactionId), "CREDIT_ORDER", order.id)
        order.status = CreditOrderStatus.REFUNDED
        order.refundedAt = clock.instant()
        return "refunded"
    }

    private fun ignored(reason: String): String {
        logger.warn("그로블 웹훅 무시: {}", reason)
        return "ignored"
    }

    private companion object {
        val logger = LoggerFactory.getLogger(GroblePaymentEventService::class.java)
    }
}
