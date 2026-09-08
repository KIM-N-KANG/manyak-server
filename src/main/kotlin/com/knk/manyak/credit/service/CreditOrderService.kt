package com.knk.manyak.credit.service

import com.knk.manyak.credit.config.GroblePaymentProperties
import com.knk.manyak.credit.dto.CreateCreditOrderResponse
import com.knk.manyak.credit.dto.CreditOrderResponse
import com.knk.manyak.credit.dto.CreditProductResponse
import com.knk.manyak.credit.dto.CreditProductsResponse
import com.knk.manyak.credit.entity.CreditOrder
import com.knk.manyak.credit.entity.CreditOrderProvider
import com.knk.manyak.credit.repository.CreditOrderRepository
import com.knk.manyak.global.security.SuspensionGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class CreditOrderService(
    private val properties: GroblePaymentProperties,
    private val orders: CreditOrderRepository,
    private val suspensionGuard: SuspensionGuard,
) {
    fun products(): CreditProductsResponse = CreditProductsResponse(
        items = properties.products.map {
            CreditProductResponse(it.id, it.base, it.bonus, it.totalCredits, it.webPriceKrw, it.appPriceKrw)
        },
    )

    @Transactional
    fun create(userId: Long, productId: String): CreateCreditOrderResponse {
        suspensionGuard.requireActive(userId)
        val product = properties.products.find { it.id == productId }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 이프 상품입니다.")
        if (product.paymentUrl.isBlank() || properties.webhookSecret.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이프 결제가 설정되지 않았습니다.")
        }
        // ponytail: 결제를 마치지 않은 PENDING 주문 누적은 수용한다. 만료·정리 배치는 이번 범위에 없다.
        val order = orders.save(
            CreditOrder(
                userId = userId,
                productId = product.id,
                provider = CreditOrderProvider.GROBLE,
                priceKrw = product.webPriceKrw,
                creditAmount = product.totalCredits,
            ),
        )
        val separator = if ('?' in product.paymentUrl) '&' else '?'
        return CreateCreditOrderResponse(order.publicId, "${product.paymentUrl}${separator}ref=${order.publicId}")
    }

    @Transactional(readOnly = true)
    fun get(userId: Long, orderId: UUID): CreditOrderResponse {
        val order = orders.findByPublicId(orderId)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다.")
        return CreditOrderResponse(order.publicId, order.productId, order.status, order.creditAmount, order.createdAt, order.completedAt)
    }
}
