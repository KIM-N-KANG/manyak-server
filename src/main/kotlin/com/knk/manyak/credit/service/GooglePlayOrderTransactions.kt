package com.knk.manyak.credit.service

import com.knk.manyak.credit.config.CreditProduct
import com.knk.manyak.credit.dto.GooglePlayPurchaseResponse
import com.knk.manyak.credit.entity.*
import com.knk.manyak.credit.repository.CreditOrderRepository
import com.knk.manyak.credit.repository.CreditTransactionRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

@Service
class GooglePlayOrderTransactions(
    private val orders: CreditOrderRepository,
    private val transactions: CreditTransactionRepository,
    private val wallets: CreditWalletService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun existing(userId: Long, productId: String, reference: String): GooglePlayPurchaseResponse? {
        val order = orders.findByProviderRef(reference) ?: return null
        if (order.provider != CreditOrderProvider.GOOGLE_PLAY || order.userId != userId || order.productId != productId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 다른 구매에 사용된 토큰입니다.")
        }
        // 환불된 주문도 다시 적립하지 않는다. balance는 다른 이프 API와 같은 현재 사용 가능 잔액이다.
        return GooglePlayPurchaseResponse(order.publicId, wallets.balanceOf(userId))
    }

    @Transactional
    fun complete(userId: Long, product: CreditProduct, reference: String): GooglePlayPurchaseResponse {
        // UNIQUE 경합은 호출 서비스가 트랜잭션 롤백 이후 기존 주문을 다시 읽어 처리한다.
        val order = orders.saveAndFlush(CreditOrder(userId = userId, productId = product.id,
            provider = CreditOrderProvider.GOOGLE_PLAY, priceKrw = product.appPriceKrw,
            creditAmount = product.totalCredits, providerRef = reference,
            status = CreditOrderStatus.COMPLETED, completedAt = clock.instant()))
        val key = "google:$reference"
        val reward = wallets.reward(userId, order.creditAmount, CreditReason.PURCHASE, key, "CREDIT_ORDER", order.id)
        check(reward.rewarded) { "Google 구매 적립 원장이 중복되었습니다." }
        order.creditTransactionId = checkNotNull(transactions.findByIdempotencyKey(key)).id
        return GooglePlayPurchaseResponse(order.publicId, reward.balance)
    }

    @Transactional
    fun reverse(reference: String): String {
        val order = orders.findByProviderRefForUpdate(reference) ?: return "ignored"
        if (order.provider != CreditOrderProvider.GOOGLE_PLAY || order.status != CreditOrderStatus.COMPLETED) return "ignored"
        val reversed = wallets.reverseLot(order.userId, checkNotNull(order.creditTransactionId), "CREDIT_ORDER", order.id)
        order.reversalShortfall = order.creditAmount - reversed
        order.status = CreditOrderStatus.REFUNDED
        order.refundedAt = clock.instant()
        return "reversed"
    }
}
