package com.knk.manyak.credit.google

interface GooglePlayPurchaseClient {
    fun getProductPurchase(packageName: String, productId: String, token: String): GoogleProductPurchase
    fun listVoidedPurchases(packageName: String, startTimeMillis: Long): List<GoogleVoidedPurchase>
}

// 토큰을 포함하는 객체는 data class의 자동 toString으로 원문을 노출하지 않는다.
class GoogleProductPurchase(
    val productId: String?,
    val purchaseState: Int,
    val consumptionState: Int? = null,
    val purchaseType: Int? = null,
    val orderId: String? = null,
    val acknowledgementState: Int? = null,
)

class GoogleVoidedPurchase(
    val purchaseToken: String,
    val orderId: String? = null,
    val voidedTimeMillis: Long? = null,
    val voidedReason: Int? = null,
)

class GooglePlayVerificationException : RuntimeException("Google Play 구매 검증 실패")
class GooglePlayUnavailableException : RuntimeException("Google Play 연동 실패")
