package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.google.GooglePlayPurchaseClient
import com.knk.manyak.credit.google.GoogleProductPurchase
import com.knk.manyak.support.DatabaseCleaner
import com.knk.manyak.credit.config.GooglePlayPaymentProperties
import com.knk.manyak.credit.config.GroblePaymentProperties
import com.knk.manyak.credit.entity.*
import com.knk.manyak.credit.google.*
import com.knk.manyak.credit.repository.*
import com.knk.manyak.credit.service.*
import com.knk.manyak.credit.scheduler.GooglePlayVoidedPurchaseReconciler
import com.knk.manyak.global.security.SuspensionGuard
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.client.RestTestClient

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "manyak.payment.google-play.service-account-json=test-only-credentials",
    "manyak.payment.google-play.package-name=app.manyak.test",
    "manyak.payment.google-play.voided-reconcile.enabled=false",
])
class GooglePlayPurchaseIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @MockitoBean private lateinit var google: GooglePlayPurchaseClient

    @MockitoSpyBean private lateinit var walletService: CreditWalletService
    @Autowired private lateinit var orders: CreditOrderRepository
    @Autowired private lateinit var transactions: CreditTransactionRepository
    @Autowired private lateinit var lots: CreditLotRepository
    @Autowired private lateinit var wallets: CreditWalletRepository
    @Autowired private lateinit var orderTransactions: GooglePlayOrderTransactions
    @Autowired private lateinit var products: GroblePaymentProperties
    @Autowired private lateinit var guard: SuspensionGuard
    @Autowired private lateinit var meters: MeterRegistry
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val mapper = ObjectMapper()

    @BeforeEach fun clean() = cleaner.cleanAll()

    @Test fun `정상 구매는 이프를 적립하고 주문 공개 ID를 반환한다`() {
        val user = users.save(User(nickname = "구매 회원"))
        `when`(google.getProductPurchase("app.manyak.test", "if_5000", "test-purchase"))
            .thenReturn(GoogleProductPurchase("if_5000", 0))
        client.post().uri("/api/v1/users/me/credits/purchases/google")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("productId" to "if_5000", "purchaseToken" to "test-purchase"))
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.orderId").isNotEmpty
            .jsonPath("$.balance").isEqualTo(5000)
    }
    @Test fun `재전송은 동일 주문이고 적립 및 5년 로트는 한 번이다`() {
        val user = user()
        receipt()
        val first = buy(user)
        val second = buy(user)
        assertThat(first["orderId"].asText()).isEqualTo(second["orderId"].asText())
        assertThat(second["balance"].asLong()).isEqualTo(5000)
        val order = orders.findAll().single()
        assertThat(order.provider).isEqualTo(CreditOrderProvider.GOOGLE_PLAY)
        assertThat(order.providerRef).isEqualTo(purchaseTokenHash("test-purchase"))
        assertThat(order.priceKrw).isEqualTo(6700)
        assertThat(order.creditAmount).isEqualTo(5000)
        assertThat(order.status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(order.completedAt).isNotNull()
        val tx = transactions.findAll().single()
        assertThat(tx.id).isEqualTo(order.creditTransactionId)
        assertThat(tx.idempotencyKey).isEqualTo("google:" + purchaseTokenHash("test-purchase"))
        assertThat(tx.refId).isEqualTo(order.id)
        assertThat(tx.reason).isEqualTo(CreditReason.PURCHASE)
        val lot = lots.findAll().single()
        assertThat(lot.originalAmount).isEqualTo(5000)
        assertThat(lot.expiresAt).isEqualTo(lot.createdAt.atZone(ZoneOffset.UTC).plusYears(5).toInstant())
        verify(google, times(1)).getProductPurchase("app.manyak.test", "if_5000", "test-purchase")
    }

    @Test fun `같은 토큰 동시 두 요청은 한 주문과 한 적립으로 끝난다`() {
        val user = user()
        val barrier = CyclicBarrier(2)
        `when`(google.getProductPurchase("app.manyak.test", "if_5000", "test-purchase")).thenAnswer {
            barrier.await(10, TimeUnit.SECONDS)
            GoogleProductPurchase("if_5000", 0)
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map { pool.submit(Callable { buy(user) }) }
            val results = requests.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(results.map { it["orderId"].asText() }.distinct()).hasSize(1)
            assertThat(orders.count()).isEqualTo(1)
            assertThat(transactions.count()).isEqualTo(1)
            assertThat(lots.count()).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `타인 토큰과 같은 토큰의 다른 상품은 400이다`() {
        receipt()
        val owner = user()
        buy(owner)
        buy(user(), status = 400)
        buy(owner, product = "if_2000", status = 400)
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `미지원 상품은 Google 호출 전에 400이다`() {
        buy(user(), product = "unknown", status = 400)
        verifyNoInteractions(google)
    }

    @Test fun `취소 대기 상품 불일치 및 테스트 구매는 거부한다`() {
        val user = user()
        for (receipt in listOf(GoogleProductPurchase("if_5000", 1), GoogleProductPurchase("if_5000", 2),
            GoogleProductPurchase("if_2000", 0), GoogleProductPurchase("if_5000", 0, purchaseType = 0))) {
            `when`(google.getProductPurchase("app.manyak.test", "if_5000", "test-purchase")).thenReturn(receipt)
            buy(user, status = 400)
        }
        assertThat(orders.count()).isZero()
        assertThat(transactions.count()).isZero()
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = [1, 2, 3, -1])
    fun `프로모션 리워드 미지원 유형은 적립 없이 거부한다`(type: Int) {
        val owner = user()
        receipt(type = type)
        val rejected = meters.counter("manyak.payment.google.purchase", "result", "rejected")
        val before = rejected.count()
        val response = buy(owner, status = 400)
        assertThat(response["message"].asText()).contains("지원하지 않는 구매 유형")
        assertThat(rejected.count() - before).isEqualTo(1.0)
        assertThat(orders.count()).isZero()
        assertThat(transactions.count()).isZero()
        assertThat(lots.count()).isZero()
    }

    @Test fun `dev 테스트 허용도 프로모션과 리워드를 허용하지 않는다`() {
        val owner = user()
        val service = GooglePlayPurchaseService(settings(allowTest = true), products, google, orderTransactions, guard, meters)
        for (type in listOf(1, 2)) {
            receipt(type = type)
            org.assertj.core.api.Assertions.assertThatThrownBy { service.purchase(owner.id, "if_5000", "test-purchase") }
                .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
                .hasMessageContaining("400").hasMessageContaining("지원하지 않는 구매 유형")
        }
        assertThat(orders.count()).isZero()
        assertThat(transactions.count()).isZero()
    }

    @Test fun `테스트 허용 설정이면 테스트 구매도 적립한다`() {
        val user = user()
        receipt(type = 0)
        val service = GooglePlayPurchaseService(settings(allowTest = true), products, google, orderTransactions, guard, meters)
        assertThat(service.purchase(user.id, "if_5000", "test-purchase").balance).isEqualTo(5000)
    }

    @Test fun `Google 검증 실패는 400 일시 장애는 502이다`() {
        val user = user()
        `when`(google.getProductPurchase("app.manyak.test", "if_5000", "test-purchase"))
            .thenThrow(GooglePlayVerificationException()).thenThrow(GooglePlayUnavailableException())
        buy(user, status = 400)
        buy(user, status = 502)
        assertThat(orders.count()).isZero()
    }

    @Test fun `게스트는 401 정지 회원은 403이며 Google을 호출하지 않는다`() {
        client.post().uri("/api/v1/users/me/credits/purchases/google")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to "if_5000", "purchaseToken" to "test-purchase"))
            .exchange().expectStatus().isUnauthorized
        buy(users.save(User(nickname = "정지 회원", status = UserStatus.SUSPENDED)), status = 403)
        verifyNoInteractions(google)
    }

    @Test fun `빈 토큰은 400이며 원장에 기록하지 않는다`() {
        buy(user(), token = "", status = 400)
        assertThat(orders.count()).isZero()
        verifyNoInteractions(google)
    }

    @Test fun `대사는 남은 로트를 회수하고 부족분과 환불 시각을 기록하며 재실행은 멱등이다`() {
        val user = user()
        receipt()
        buy(user)
        val order = orders.findAll().single()
        jdbc.update("UPDATE credit_lots SET remaining = 1200 WHERE transaction_id = ?", order.creditTransactionId)
        jdbc.update("UPDATE credit_wallets SET balance = 1200 WHERE user_id = ?", user.id)
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val start = now.minusSeconds(48 * 3600).toEpochMilli()
        `when`(google.listVoidedPurchases("app.manyak.test", start)).thenReturn(listOf(GoogleVoidedPurchase("test-purchase"), GoogleVoidedPurchase("unmatched")))
        val reconciler = GooglePlayVoidedPurchaseReconciler(settings(), google, orderTransactions, meters, Clock.fixed(now, ZoneOffset.UTC))
        reconciler.run()
        reconciler.run()
        val saved = orders.findById(order.id).orElseThrow()
        assertThat(saved.status).isEqualTo(CreditOrderStatus.REFUNDED)
        assertThat(saved.reversalShortfall).isEqualTo(3800)
        assertThat(saved.refundedAt).isNotNull()
        assertThat(wallets.findByUserId(user.id)!!.balance).isZero()
        assertThat(lots.findAll().single().remaining).isZero()
        assertThat(transactions.findAll().filter { it.reason == CreditReason.PURCHASE_REVERSAL }.single().amount).isEqualTo(-1200)
        assertThat(buy(user)["balance"].asLong()).isZero()
        assertThat(transactions.count()).isEqualTo(2)
    }

    @Test fun `미사용 환불은 전량 회수하고 동시 대사도 한 번만 회수한다`() {
        receipt()
        buy(user())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val jobs = (1..2).map { pool.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                orderTransactions.reverse(purchaseTokenHash("test-purchase"))
            }) }
            assertThat(jobs.map { it.get(20, TimeUnit.SECONDS) }).containsExactlyInAnyOrder("reversed", "ignored")
            assertThat(orders.findAll().single().reversalShortfall).isZero()
            assertThat(transactions.findAll().single { it.reason == CreditReason.PURCHASE_REVERSAL }.amount).isEqualTo(-5000)
        } finally { pool.shutdownNow() }
    }

    @Test fun `개별 회수 실패는 다음 주문에 영향을 주지 않고 실패 주문은 롤백한다`() {
        receipt()
        val owner = user()
        buy(owner)
        receipt(token = "second")
        buy(owner, token = "second")
        val broken = orders.findByProviderRef(purchaseTokenHash("test-purchase"))!!
        jdbc.update("UPDATE credit_orders SET credit_transaction_id = NULL WHERE id = ?", broken.id)
        val now = Instant.parse("2026-09-09T00:00:00Z")
        `when`(google.listVoidedPurchases("app.manyak.test", now.minusSeconds(48 * 3600).toEpochMilli()))
            .thenReturn(listOf(GoogleVoidedPurchase("test-purchase"), GoogleVoidedPurchase("second")))
        GooglePlayVoidedPurchaseReconciler(settings(), google, orderTransactions, meters, Clock.fixed(now, ZoneOffset.UTC)).run()
        assertThat(orders.findById(broken.id).orElseThrow().status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(orders.findByProviderRef(purchaseTokenHash("second"))!!.status).isEqualTo(CreditOrderStatus.REFUNDED)
    }

    @Test fun `시크릿이나 패키지가 없으면 대사는 호출 없이 끝난다`() {
        for (props in listOf(GooglePlayPaymentProperties(), GooglePlayPaymentProperties(serviceAccountJson = "test-only"),
            GooglePlayPaymentProperties(packageName = "app.manyak.test"))) {
            GooglePlayVoidedPurchaseReconciler(props, google, orderTransactions, meters).run()
        }
        verifyNoInteractions(google)
    }


    @Test fun `적립 도중 실패하면 완료 주문과 로트가 남지 않는다`() {
        val owner = user()
        receipt()
        doThrow(IllegalStateException("test-only-wallet-failure")).`when`(walletService)
            .reward(org.mockito.ArgumentMatchers.eq(owner.id), org.mockito.ArgumentMatchers.eq(5000L),
                org.mockito.ArgumentMatchers.eq(CreditReason.PURCHASE) ?: CreditReason.PURCHASE, org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("CREDIT_ORDER"), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.isNull())
        buy(owner, status = 500)
        assertThat(orders.count()).isZero()
        assertThat(transactions.count()).isZero()
        assertThat(lots.count()).isZero()
    }

    @Test fun `구매 결과 카운터는 완료 중복 거부 오류를 구분한다`() {
        fun counter(result: String) = meters.counter("manyak.payment.google.purchase", "result", result).count()
        val results = listOf("completed", "duplicate", "rejected", "error")
        val before = results.associateWith { counter(it) }
        val owner = user()
        receipt()
        buy(owner)
        buy(owner)
        buy(owner, product = "unknown", status = 400)
        `when`(google.getProductPurchase("app.manyak.test", "if_5000", "error-token")).thenThrow(GooglePlayUnavailableException())
        buy(owner, token = "error-token", status = 502)
        results.forEach { assertThat(counter(it) - before.getValue(it)).isEqualTo(1.0) }
    }

    private fun settings(allowTest: Boolean = false) = GooglePlayPaymentProperties("test-only", "app.manyak.test", allowTest)
    private fun user() = users.save(User(nickname = "구매 회원"))
    private fun receipt(token: String = "test-purchase", type: Int? = null) {
        `when`(google.getProductPurchase("app.manyak.test", "if_5000", token))
            .thenReturn(GoogleProductPurchase("if_5000", 0, purchaseType = type))
    }
    private fun buy(user: User, product: String = "if_5000", token: String = "test-purchase", status: Int = 200): JsonNode {
        val body = client.post().uri("/api/v1/users/me/credits/purchases/google")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to product, "purchaseToken" to token))
            .exchange().expectStatus().isEqualTo(status).expectBody(String::class.java).returnResult().responseBody!!
        return mapper.readTree(body)
    }

}
