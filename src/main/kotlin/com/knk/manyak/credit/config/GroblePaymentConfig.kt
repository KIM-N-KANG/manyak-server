package com.knk.manyak.credit.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("manyak.payment.groble")
class GroblePaymentProperties(
    val products: List<CreditProduct>,
    val webhookSecret: String = "",
) {
    init {
        require(products.size == 5) { "이프 충전 상품은 5종이어야 합니다." }
        require(products.map { it.id }.distinct().size == products.size) { "이프 상품 ID는 중복될 수 없습니다." }
    }
}

// 링크·시크릿은 미설정 기동을 허용한다. 주문 생성 시 선택 상품의 링크와 시크릿을 확인해 503으로 막는다.
data class CreditProduct(
    val id: String,
    val base: Long,
    val bonus: Long,
    val webPriceKrw: Long,
    val appPriceKrw: Long,
    val paymentUrl: String = "",
) {
    init {
        require(id.isNotBlank() && id.length <= 32) { "상품 ID는 1~32자여야 합니다." }
        require(base > 0 && bonus >= 0 && webPriceKrw > 0 && appPriceKrw > 0) {
            "기본 이프·가격은 양수, 보너스는 0 이상이어야 합니다."
        }
        require(base <= Long.MAX_VALUE - bonus) { "상품 총량이 저장 범위를 초과합니다." }
    }

    val totalCredits: Long get() = base + bonus
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroblePaymentProperties::class)
class GroblePaymentConfig
