package com.knk.manyak.credit.google

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.io.IOException

class RestGooglePlayPurchaseClientTests {
    private val builder = RestClient.builder().baseUrl("https://androidpublisher.googleapis.com")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = RestGooglePlayPurchaseClient(builder.build()) { "test-only-access-token" }
    private val productUrl = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/app.test/purchases/products/if_5000/tokens/test-token"

    @Test fun `구매 상태와 선택 필드를 읽고 Bearer로 인증한다`() {
        server.expect(requestTo(productUrl)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-only-access-token"))
            .andRespond(withSuccess("""{"purchaseState":0,"productId":"if_5000","consumptionState":0,"purchaseType":0,"orderId":"test-order","acknowledgementState":1}""", MediaType.APPLICATION_JSON))
        val receipt = client.getProductPurchase("app.test", "if_5000", "test-token")
        assertThat(receipt.productId).isEqualTo("if_5000")
        assertThat(receipt.purchaseState).isZero()
        assertThat(receipt.consumptionState).isZero()
        assertThat(receipt.purchaseType).isZero()
        assertThat(receipt.orderId).isEqualTo("test-order")
        assertThat(receipt.acknowledgementState).isEqualTo(1)
        server.verify()
    }

    @Test fun `생략된 productId는 Google이 검증한 요청 경로를 사용하고 purchaseType은 일반 구매다`() {
        server.expect(requestTo(productUrl)).andRespond(withSuccess("""{"purchaseState":0}""", MediaType.APPLICATION_JSON))
        val receipt = client.getProductPurchase("app.test", "if_5000", "test-token")
        assertThat(receipt.productId).isEqualTo("if_5000")
        assertThat(receipt.purchaseType).isNull()
        server.verify()
    }

    @Test fun `Google이 명시한 다른 상품은 요청 상품으로 덮어쓰지 않는다`() {
        server.expect(requestTo(productUrl)).andRespond(withSuccess("""{"purchaseState":0,"productId":"if_2000"}""", MediaType.APPLICATION_JSON))
        assertThat(client.getProductPurchase("app.test", "if_5000", "test-token").productId).isEqualTo("if_2000")
    }

    @Test fun `필수 상태 누락을 구매 완료 0으로 해석하지 않는다`() {
        server.expect(requestTo(productUrl)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertThatThrownBy { client.getProductPurchase("app.test", "if_5000", "test-token") }
            .isInstanceOf(GooglePlayUnavailableException::class.java)
    }

    @Test fun `400과 404는 검증 실패로 바꾸고 원문을 포함하지 않는다`() {
        for (status in listOf(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND)) {
            server.reset()
            server.expect(requestTo(productUrl)).andRespond(withStatus(status).body("private-test-token"))
            assertThatThrownBy { client.getProductPurchase("app.test", "if_5000", "test-token") }
                .isInstanceOf(GooglePlayVerificationException::class.java)
                .hasMessageNotContaining("private-test-token").hasNoCause()
        }
    }

    @Test fun `5xx와 네트워크 및 인증 오류는 민감한 원인 없이 연동 실패로 변환한다`() {
        server.expect(requestTo(productUrl)).andRespond(withServerError())
        assertThatThrownBy { client.getProductPurchase("app.test", "if_5000", "test-token") }
            .isInstanceOf(GooglePlayUnavailableException::class.java).hasNoCause()
        server.reset()
        server.expect(requestTo(productUrl)).andRespond(withException(IOException("private-test-token")))
        assertThatThrownBy { client.getProductPurchase("app.test", "if_5000", "test-token") }
            .isInstanceOf(GooglePlayUnavailableException::class.java).hasNoCause()
        val failing = RestGooglePlayPurchaseClient(builder.build()) { throw IllegalArgumentException("private-test-key") }
        assertThatThrownBy { failing.getProductPurchase("app.test", "if_5000", "test-token") }
            .isInstanceOf(GooglePlayUnavailableException::class.java).hasNoCause()
    }

    @Test fun `환불 목록의 모든 페이지를 token 파라미터로 읽는다`() {
        val url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/app.test/purchases/voidedpurchases?startTime=1000"
        server.expect(requestTo(url)).andRespond(withSuccess("""{"tokenPagination":{"nextPageToken":"next+/="},"voidedPurchases":[{"purchaseToken":"first","orderId":"order-1","voidedTimeMillis":"2000","voidedReason":1}]}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$url&token=next%2B%2F%3D")).andRespond(withSuccess("""{"voidedPurchases":[{"purchaseToken":"second"}]}""", MediaType.APPLICATION_JSON))
        val purchases = client.listVoidedPurchases("app.test", 1000)
        assertThat(purchases.map { it.purchaseToken }).containsExactly("first", "second")
        assertThat(purchases.first().voidedTimeMillis).isEqualTo(2000)
        assertThat(purchases.first().voidedReason).isEqualTo(1)
        server.verify()
    }

    @Test fun `환불 빈 목록은 정상이며 반복 페이지는 무한 순회하지 않는다`() {
        val url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/app.test/purchases/voidedpurchases?startTime=1000"
        server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        assertThat(client.listVoidedPurchases("app.test", 1000)).isEmpty()
        server.reset()
        server.expect(requestTo(url)).andRespond(withSuccess("""{"tokenPagination":{"nextPageToken":"same"}}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$url&token=same")).andRespond(withSuccess("""{"tokenPagination":{"nextPageToken":"same"}}""", MediaType.APPLICATION_JSON))
        assertThatThrownBy { client.listVoidedPurchases("app.test", 1000) }.isInstanceOf(GooglePlayUnavailableException::class.java)
    }
}
