package com.knk.manyak.credit.google

import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode

class RestGooglePlayPurchaseClient(
    private val rest: RestClient,
    private val accessToken: () -> String,
) : GooglePlayPurchaseClient {
    override fun getProductPurchase(packageName: String, productId: String, token: String): GoogleProductPurchase = guarded {
        val body = rest.get()
            .uri("/androidpublisher/v3/applications/{package}/purchases/products/{product}/tokens/{token}", packageName, productId, token)
            .headers { it.setBearerAuth(accessToken()) }.retrieve().body(JsonNode::class.java)
            ?: throw GooglePlayUnavailableException()
        val state = body.path("purchaseState")
        if (!state.isIntegralNumber || !state.canConvertToInt()) throw GooglePlayUnavailableException()
        GoogleProductPurchase(
            // Google 문서상 productId는 생략 가능하다. 이때 Google이 검증한 요청 경로의 상품을 사용한다.
            productId = body.path("productId").stringValue(null) ?: productId,
            purchaseState = state.intValue(),
            consumptionState = body.optionalInt("consumptionState"),
            purchaseType = body.optionalInt("purchaseType"),
            orderId = body.path("orderId").stringValue(null),
            acknowledgementState = body.optionalInt("acknowledgementState"),
        )
    }

    override fun listVoidedPurchases(packageName: String, startTimeMillis: Long): List<GoogleVoidedPurchase> = guarded {
        val purchases = mutableListOf<GoogleVoidedPurchase>()
        val seen = mutableSetOf<String>()
        var page: String? = null
        do {
            val pageToken = page
            val body = rest.get().uri { builder ->
                val uri = builder.path("/androidpublisher/v3/applications/{package}/purchases/voidedpurchases")
                    .queryParam("startTime", startTimeMillis)
                // 토큰의 +, /, =가 query 구분자로 해석되지 않도록 URI 변수로 인코딩한다.
                if (pageToken != null) {
                    uri.queryParam("token", "{pageToken}").build(packageName, pageToken)
                } else {
                    uri.build(packageName)
                }
            }.headers { it.setBearerAuth(accessToken()) }.retrieve().body(JsonNode::class.java)
                ?: throw GooglePlayUnavailableException()
            val items = body.path("voidedPurchases")
            if (!items.isMissingNode && !items.isArray) throw GooglePlayUnavailableException()
            for (item in items) {
                val token = item.path("purchaseToken").stringValue(null)?.takeIf { it.isNotBlank() }
                    ?: throw GooglePlayUnavailableException()
                purchases += GoogleVoidedPurchase(token, item.path("orderId").stringValue(null),
                    item.path("voidedTimeMillis").stringValue(null)?.toLongOrNull(), item.optionalInt("voidedReason"))
            }
            page = body.path("tokenPagination").path("nextPageToken").stringValue(null)?.takeIf { it.isNotBlank() }
            if (page != null && !seen.add(page)) throw GooglePlayUnavailableException()
        } while (page != null)
        purchases
    }

    private fun JsonNode.optionalInt(name: String): Int? {
        val value = path(name)
        if (value.isMissingNode || value.isNull) return null
        if (!value.isIntegralNumber || !value.canConvertToInt()) throw GooglePlayUnavailableException()
        return value.intValue()
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (exception: RestClientResponseException) {
        // 응답 본문·URL에는 구매 토큰이 포함될 수 있으므로 원본 예외를 전파·로깅하지 않는다.
        if (exception.statusCode.value() in setOf(400, 404)) throw GooglePlayVerificationException()
        throw GooglePlayUnavailableException()
    } catch (_: GooglePlayVerificationException) {
        throw GooglePlayVerificationException()
    } catch (_: Exception) {
        throw GooglePlayUnavailableException()
    }
}
