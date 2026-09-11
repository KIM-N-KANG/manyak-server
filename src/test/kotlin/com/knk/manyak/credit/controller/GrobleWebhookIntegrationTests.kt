package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.*
import com.knk.manyak.credit.repository.*
import com.knk.manyak.credit.service.CreditWalletService
import com.knk.manyak.support.DatabaseCleaner
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val SECRET = "test-only-groble-secret"

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.payment.groble.webhook-secret=$SECRET"])
class GrobleWebhookIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var orders: CreditOrderRepository
    @Autowired private lateinit var transactions: CreditTransactionRepository
    @Autowired private lateinit var lots: CreditLotRepository
    @Autowired private lateinit var wallets: CreditWalletRepository
    @Autowired private lateinit var walletService: CreditWalletService
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var meters: MeterRegistry
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach fun clean() = cleaner.cleanAll()

    @Test fun `raw UTF8 서명 완료 재전송은 한 번만 적립하고 5년 로트를 만든다`() {
        val order = order()
        val body = completed(order, extra = ",\"memo\": \"한글 공백 그대로\"")
        val before = count("completed")
        send(body)
        send(body)
        val saved = orders.findById(order.id).orElseThrow()
        assertThat(saved.status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(saved.providerRef).isEqualTo("merchant-${order.id}")
        assertThat(saved.completedAt).isNotNull()
        val tx = transactions.findAll().single()
        assertThat(saved.creditTransactionId).isEqualTo(tx.id)
        assertThat(tx.amount).isEqualTo(5000)
        assertThat(tx.reason).isEqualTo(CreditReason.PURCHASE)
        assertThat(tx.idempotencyKey).isEqualTo("groble:event-${order.id}")
        assertThat(tx.refType).isEqualTo("CREDIT_ORDER")
        assertThat(tx.refId).isEqualTo(order.id)
        assertThat(wallets.findByUserId(order.userId)!!.balance).isEqualTo(5000)
        val lot = lots.findAll().single()
        assertThat(lot.expiresAt).isEqualTo(lot.createdAt.atZone(ZoneOffset.UTC).plusYears(5).toInstant())
        assertThat(count("completed") - before).isEqualTo(1.0)
    }

    @Test fun `동시 완료 재전송도 주문 락으로 한 번만 적립한다`() {
        val body = completed(order())
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit { send(body) } }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertThat(transactions.findAll()).hasSize(1)
        assertThat(lots.findAll()).hasSize(1)
    }

    @Test fun `Previous 서명은 현재 서명 불일치와 함께 와도 통과한다`() {
        val body = completed(order())
        val ts = Instant.now().epochSecond.toString()
        send(body, ts, signature = "not-hex", previous = sign(body, ts))
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `타임스탬프와 서명을 먼저 검증하고 깨진 JSON은 400이다`() {
        val before = count("invalid")
        for (ts in listOf("abc", "", Long.MAX_VALUE.toString(), (Instant.now().epochSecond - 301).toString(), (Instant.now().epochSecond + 600).toString())) {
            send("{broken", ts, status = 401)
        }
        send("{broken", signature = "00", status = 401)
        send("{broken", status = 400)
        val body = completed(order())
        send(body + " ", signature = sign(body, Instant.now().epochSecond.toString()), status = 401)
        assertThat(count("invalid") - before).isEqualTo(8.0)
        assertThat(transactions.count()).isZero()
    }

    @Test fun `필수 헤더가 없으면 401이다`() {
        client.post().uri("/api/v1/webhooks/groble").contentType(MediaType.APPLICATION_JSON).body("{}")
            .exchange().expectStatus().isUnauthorized
    }

    @Test fun `판매 참조 없음 잘못된 UUID 미매칭 금액 불일치는 200으로 무시한다`() {
        val order = order()
        for (body in listOf(
            completed(order).replace("\"sellerReference\":\"${order.publicId}\",", ""),
            completed(order).replace(order.publicId.toString(), "invalid"),
            completed(order).replace(order.publicId.toString(), UUID.randomUUID().toString()),
            completed(order).replace("4800", "4999"),
            completed(order).replace("4800", "4800.5"),
            completed(order).replace("payment.completed", "subscription.created"),
        )) send(body)
        assertThat(transactions.count()).isZero()
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.PENDING)
    }

    @Test fun `이미 적립된 같은 멱등 키는 PENDING 주문의 완료 상태를 복구한다`() {
        val order = order()
        walletService.reward(order.userId, order.creditAmount, CreditReason.PURCHASE,
            "groble:event-${order.id}", "CREDIT_ORDER", order.id)
        send(completed(order))
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `환불은 구매 로트 전량을 회수하며 중복 및 뒤늦은 완료를 무시한다`() {
        val order = order()
        send(completed(order))
        val before = count("refunded")
        send(refunded(order))
        val first = orders.findById(order.id).orElseThrow()
        send(refunded(order))
        send(completed(order))
        val saved = orders.findById(order.id).orElseThrow()
        assertThat(saved.status).isEqualTo(CreditOrderStatus.REFUNDED)
        assertThat(saved.refundedAt).isNotNull().isEqualTo(first.refundedAt)
        assertThat(lots.findAll().single().remaining).isZero()
        assertThat(wallets.findByUserId(order.userId)!!.balance).isZero()
        val reversal = transactions.findAll().single { it.reason.name == "PURCHASE_REVERSAL" }
        assertThat(reversal.amount).isEqualTo(-5000)
        assertThat(reversal.refType).isEqualTo("CREDIT_ORDER")
        assertThat(reversal.refId).isEqualTo(order.id)
        assertThat(count("refunded") - before).isEqualTo(1.0)
    }

    @Test fun `일부 소진한 구매는 잔여만 회수하고 다른 로트는 보존한다`() {
        val order = order()
        send(completed(order))
        walletService.deduct(order.userId, 4800, CreditReason.CHAT_TURN)
        walletService.reward(order.userId, 250, CreditReason.ATTENDANCE_REWARD, "attendance-test")
        send(refunded(order))
        assertThat(wallets.findByUserId(order.userId)!!.balance).isEqualTo(250)
        assertThat(transactions.findAll().single { it.reason.name == "PURCHASE_REVERSAL" }.amount).isEqualTo(-200)
        assertThat(lots.findAll().sumOf { it.remaining }).isEqualTo(250)
    }

    @Test fun `전부 소진한 구매도 환불 상태를 기록하고 0원 원장은 만들지 않는다`() {
        val order = order()
        send(completed(order))
        walletService.deduct(order.userId, 5000, CreditReason.CHAT_TURN)
        send(refunded(order))
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.REFUNDED)
        assertThat(transactions.count()).isEqualTo(2)
        assertThat(wallets.findByUserId(order.userId)!!.balance).isZero()
    }

    @Test fun `부분 환불과 미매칭 환불은 회수하지 않는다`() {
        val order = order()
        send(completed(order))
        send(refunded(order, true))
        send(refunded(order).replace("merchant-${order.id}", "unmatched"))
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(wallets.findByUserId(order.userId)!!.balance).isEqualTo(5000)
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `구매는 EARN 회수는 EXPIRE로 ALL과 필터에 노출하며 제목은 null이다`() {
        val order = order()
        send(completed(order))
        send(refunded(order))
        val user = users.findById(order.userId).orElseThrow()
        val token = "Bearer ${jwt.issueAccessToken(user.publicId)}"
        client.get().uri("/api/v1/users/me/credits/transactions?type=ALL").header("Authorization", token)
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.items.length()").isEqualTo(2)
        for ((type, reason) in listOf("EARN" to "PURCHASE", "EXPIRE" to "PURCHASE_REVERSAL")) {
            client.get().uri("/api/v1/users/me/credits/transactions?type=$type").header("Authorization", token)
                .exchange().expectStatus().isOk.expectBody()
                .jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].type").isEqualTo(type)
                .jsonPath("$.items[0].reason").isEqualTo(reason)
                .jsonPath("$.items[0].title").isEmpty
        }
    }

    @Test fun `다른 주문에서 이벤트 id를 재사용해도 완료로 연결하지 않는다`() {
        val first = order()
        val second = order()
        send(completed(first))
        send(completed(second).replace("event-${second.id}", "event-${first.id}"))
        assertThat(orders.findById(second.id).orElseThrow().status).isEqualTo(CreditOrderStatus.PENDING)
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `providerRef 충돌로 주문 갱신이 실패하면 적립도 롤백된다`() {
        val first = order()
        val second = order()
        send(completed(first))
        send(completed(second).replace("merchant-${second.id}", "merchant-${first.id}"), status = 500)
        assertThat(orders.findById(second.id).orElseThrow().status).isEqualTo(CreditOrderStatus.PENDING)
        assertThat(transactions.count()).isEqualTo(1)
        assertThat(lots.count()).isEqualTo(1)
        assertThat(wallets.findByUserId(second.userId)!!.balance).isZero()
    }

    @Test fun `동시 환불 재전송은 회수 원장 하나만 기록한다`() {
        val order = order()
        send(completed(order))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { pool.submit { send(refunded(order)) } }
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertThat(transactions.count()).isEqualTo(2)
        assertThat(wallets.findByUserId(order.userId)!!.balance).isZero()
    }

    @Test fun `환불 여부 필드가 없는 이벤트는 회수하지 않는다`() {
        val order = order()
        send(completed(order))
        send(refunded(order).replace("\"partialRefund\":false,", ""))
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `OpenAPI에 웹훅 인증과 오류 응답을 문서화한다`() {
        val json = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val operation = ObjectMapper().readTree(json).path("paths").path("/api/v1/webhooks/groble").path("post")
        assertThat(operation.path("security").size()).isZero()
        for (status in listOf("400", "401", "503")) {
            assertThat(operation.path("responses").path(status).path("content").path("application/json")
                .path("schema").path("\$ref").stringValue()).isEqualTo("#/components/schemas/ApiErrorResponse")
        }
    }

    @Test fun `환불이 먼저 도착하면 중복 표식을 하나만 보관하고 완료 때 적립하지 않는다`() {
        val order = order()
        val before = count("marked")
        send(refunded(order))
        send(refunded(order))
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM groble_refund_marks", Long::class.java)).isEqualTo(1)
        assertThat(count("marked") - before).isEqualTo(2.0)
        send(completed(order))
        val saved = orders.findById(order.id).orElseThrow()
        assertThat(saved.status).isEqualTo(CreditOrderStatus.REFUNDED)
        assertThat(saved.completedAt).isNotNull().isEqualTo(saved.refundedAt)
        assertThat(saved.creditTransactionId).isNull()
        assertThat(saved.providerRef).isEqualTo("merchant-${order.id}")
        assertThat(transactions.count()).isZero()
        assertThat(lots.count()).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM groble_refund_marks", Long::class.java)).isZero()
    }

    @Test fun `전액 표시여도 환불 금액이 다르면 회수하지 않는다`() {
        val order = order()
        send(completed(order))
        send(refunded(order).replace("4800", "1000"))
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(transactions.count()).isEqualTo(1)
    }

    @Test fun `회수 부족분은 미사용이면 0 일부 소진이면 소진량이다`() {
        for (spent in listOf(0L, 200L, 5000L)) {
            val order = order()
            send(completed(order))
            if (spent > 0) walletService.deduct(order.userId, spent, CreditReason.CHAT_TURN)
            send(refunded(order))
            assertThat(jdbc.queryForObject("SELECT reversal_shortfall FROM credit_orders WHERE id = ?", Long::class.java, order.id))
                .isEqualTo(spent)
        }
    }

    @Test fun `역순 환불 금액이 다르면 정상 적립하고 표식을 삭제한다`() {
        val order = order()
        send(refunded(order).replace("4800", "1000"))
        send(completed(order))
        assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.COMPLETED)
        assertThat(wallets.findByUserId(order.userId)!!.balance).isEqualTo(5000)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM groble_refund_marks", Long::class.java)).isZero()
    }

    @Test fun `완료와 환불 동시 수신은 직렬화되어 최종 환불 잔액 0이다`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) {
                val order = order()
                val start = java.util.concurrent.CyclicBarrier(2)
                val futures = listOf(completed(order), refunded(order)).map { body ->
                    pool.submit { start.await(5, TimeUnit.SECONDS); send(body) }
                }
                futures.forEach { it.get(20, TimeUnit.SECONDS) }
                assertThat(orders.findById(order.id).orElseThrow().status).isEqualTo(CreditOrderStatus.REFUNDED)
                assertThat(walletService.balanceOf(order.userId)).isZero()
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM groble_refund_marks", Long::class.java)).isZero()
            }
        } finally { pool.shutdownNow() }
    }

    private fun count(result: String) = meters.find("manyak.payment.groble.webhook").tag("result", result).counter()?.count() ?: 0.0
    private fun order(): CreditOrder {
        val user = users.save(User(nickname = "웹훅 회원"))
        return orders.save(CreditOrder(userId = user.id, productId = "if_5000", provider = CreditOrderProvider.GROBLE,
            priceKrw = 4800, creditAmount = 5000))
    }
    private fun completed(order: CreditOrder, extra: String = "") = """{"id":"event-${order.id}","type":"payment.completed","data":{"object":{"sellerReference":"${order.publicId}","merchantUid":"merchant-${order.id}","pricing":{"finalAmount":4800}$extra}}}"""
    private fun refunded(order: CreditOrder, partial: Boolean = false) = """{"id":"refund-${order.id}","type":"payment.refunded","data":{"object":{"merchantUid":"merchant-${order.id}","refund":{"partialRefund":$partial,"amount":4800}}}}"""
    private fun send(body: String, ts: String = Instant.now().epochSecond.toString(), signature: String = sign(body, ts), previous: String? = null, status: Int = 200) {
        val request = client.post().uri("/api/v1/webhooks/groble")
            .header("Authorization", "Bearer ignored-webhook-token")
            .header("X-Groble-Timestamp", ts).header("X-Groble-Signature", signature)
            .header("X-Groble-Idempotency-Key", "delivery-key")
        if (previous != null) request.header("X-Groble-Signature-Previous", previous)
        request.contentType(MediaType.APPLICATION_JSON).body(body.toByteArray()).exchange().expectStatus().isEqualTo(status)
    }
    private fun sign(body: String, ts: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal("$ts.$body".toByteArray()))
    }
}

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.payment.groble.webhook-secret="])
class GrobleWebhookUnconfiguredIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Test fun `시크릿 미설정은 헤더와 JSON 검증보다 먼저 503이다`() {
        client.post().uri("/api/v1/webhooks/groble").contentType(MediaType.APPLICATION_JSON).body("{broken")
            .exchange().expectStatus().isEqualTo(503)
    }
}
