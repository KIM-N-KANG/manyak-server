package com.knk.manyak.credit.service

import com.knk.manyak.credit.config.GooglePlayPaymentProperties
import com.knk.manyak.credit.config.GroblePaymentProperties
import com.knk.manyak.credit.dto.GooglePlayPurchaseResponse
import com.knk.manyak.credit.google.*
import com.knk.manyak.global.security.SuspensionGuard
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.HexFormat

@Service
class GooglePlayPurchaseService(
    private val properties: GooglePlayPaymentProperties,
    private val products: GroblePaymentProperties,
    private val google: GooglePlayPurchaseClient,
    private val orders: GooglePlayOrderTransactions,
    private val suspensionGuard: SuspensionGuard,
    private val meters: MeterRegistry,
) {
    fun purchase(userId: Long, productId: String, token: String): GooglePlayPurchaseResponse = try {
        suspensionGuard.requireActive(userId)
        val product = products.products.find { it.id == productId }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 이프 상품입니다.")
        if (!properties.configured) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Play 결제가 설정되지 않았습니다.")
        val reference = purchaseTokenHash(token)
        orders.existing(userId, productId, reference)?.let { return counted("duplicate", it) }
        val receipt = google.getProductPurchase(properties.packageName, productId, token)
        if (receipt.purchaseState != 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "미완료 구매입니다.")
        if (receipt.productId != productId) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "구매 상품이 일치하지 않습니다.")
        when (receipt.purchaseType) {
            null -> Unit // 일반 구매만 허용한다.
            0 -> if (!properties.allowTestPurchases) {
                logger.warn("Google Play 테스트 구매 거부")
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "테스트 구매를 허용하지 않습니다.")
            }
            else -> {
                // 프로모션(1)·리워드(2)와 향후 추가될 유형도 정책에 명시되기 전에는 적립하지 않는다.
                logger.warn("Google Play 지원하지 않는 구매 유형 거부")
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 구매 유형입니다.")
            }
        }
        try {
            counted("completed", orders.complete(userId, product, reference))
        } catch (exception: DataIntegrityViolationException) {
            val existing = orders.existing(userId, productId, reference) ?: throw exception
            counted("duplicate", existing)
        }
    } catch (_: GooglePlayVerificationException) {
        count("rejected")
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Google Play 구매 검증에 실패했습니다.")
    } catch (_: GooglePlayUnavailableException) {
        count("error")
        throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google Play 연동에 실패했습니다.")
    } catch (exception: ResponseStatusException) {
        count(if (exception.statusCode.is4xxClientError) "rejected" else "error")
        throw exception
    } catch (_: Exception) {
        count("error")
        // 구매 토큰·Google 응답을 원본 예외로 노출하지 않는다.
        throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Google Play 구매 처리에 실패했습니다.")
    }

    private fun counted(result: String, response: GooglePlayPurchaseResponse): GooglePlayPurchaseResponse {
        count(result)
        return response
    }
    private fun count(result: String) = meters.counter("manyak.payment.google.purchase", "result", result).increment()
    private companion object { val logger = LoggerFactory.getLogger(GooglePlayPurchaseService::class.java) }
}

internal fun purchaseTokenHash(token: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
