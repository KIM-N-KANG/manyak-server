package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ContextConfiguration
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@ContextConfiguration(initializers = [CreditOrderPaymentTestInitializer::class])
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "manyak.payment.groble.webhook-secret=test-only-webhook-secret",
    ],
)
class CreditOrderIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun setUp() = cleaner.cleanAll()

    @Test
    fun `공개 상품 목록은 설정 순서대로 6종의 가격과 총량을 반환한다`() {
        client.get().uri("/api/v1/credits/products").exchange()
            .expectStatus().isOk.expectBody()
            .jsonPath("$.items.length()").isEqualTo(6)
            .jsonPath("$.items[*].productId").isEqualTo(listOf("if_2000", "if_5000", "if_10000", "if_30000", "if_50000", "if_100000"))
            .jsonPath("$.items[*].baseCredits").isEqualTo(listOf(2000, 4800, 9400, 27500, 45000, 88000))
            .jsonPath("$.items[*].bonusCredits").isEqualTo(listOf(0, 200, 600, 2500, 5000, 12000))
            .jsonPath("$.items[*].totalCredits").isEqualTo(listOf(2000, 5000, 10000, 30000, 50000, 100000))
            .jsonPath("$.items[*].webPriceKrw").isEqualTo(listOf(2000, 4800, 9400, 27500, 45000, 88000))
            .jsonPath("$.items[*].appPriceKrw").isEqualTo(listOf(2800, 6700, 13200, 38500, 63000, 123200))
            .jsonPath("$.items[0].paymentUrl").doesNotExist()
    }

    @Test
    fun `공개 상품 조회는 만료 토큰도 허용하지만 POST는 인증이 필요하다`() {
        client.get().uri("/api/v1/credits/products").header("Authorization", "Bearer invalid-token")
            .exchange().expectStatus().isOk
        client.post().uri("/api/v1/credits/products").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 탈퇴 계정 토큰으로 공개 상품을 조회하면 401이다`() {
        val deleted = users.save(User(nickname = "탈퇴 회원", status = UserStatus.DELETED))
        client.get().uri("/api/v1/credits/products").header("Authorization", bearer(deleted))
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효한 탈퇴 계정 토큰으로 본인 주문을 조회하면 401이다`() {
        val owner = user()
        val id = create(owner, "if_2000", "https://pay.example.test/2000?ref=")
        val token = bearer(owner)
        owner.status = UserStatus.DELETED
        users.saveAndFlush(owner)
        client.get().uri("/api/v1/users/me/credits/orders/$id").header("Authorization", token)
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `주문은 PENDING으로 저장하고 외부 UUID를 결제 링크에 담는다`() {
        val user = user()
        val orderId = create(user, "if_2000", "https://pay.example.test/2000?ref=")
        val row = jdbc.queryForMap("SELECT * FROM credit_orders WHERE public_id = ?", UUID.fromString(orderId))
        assertThat(row["user_id"]).isEqualTo(user.id)
        assertThat(row["provider"]).isEqualTo("GROBLE")
        assertThat(row["status"]).isEqualTo("PENDING")
        assertThat((row["price_krw"] as Number).toLong()).isEqualTo(2000)
        assertThat((row["credit_amount"] as Number).toLong()).isEqualTo(2000)
        assertThat(row["provider_ref"]).isNull()
        assertThat(row["credit_transaction_id"]).isNull()
        assertThat(row["completed_at"]).isNull()
        assertThat(row["refunded_at"]).isNull()
        assertThat(row["created_at"]).isNotNull()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Long::class.java)).isZero()
    }

    @Test
    fun `기존 쿼리 문자열을 보존하고 보너스 포함 총량으로 주문한다`() {
        val orderId = create(user(), "if_5000", "https://pay.example.test/5000?source=manyak&ref=")
        assertThat(jdbc.queryForObject("SELECT credit_amount FROM credit_orders WHERE public_id = ?", Long::class.java, UUID.fromString(orderId)))
            .isEqualTo(5000)
    }

    @Test
    fun `미지원 상품과 비어 있는 상품은 400이며 주문을 만들지 않는다`() {
        val user = user()
        for (product in listOf("unknown", "")) {
            client.post().uri("/api/v1/users/me/credits/orders").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to product))
                .exchange().expectStatus().isBadRequest
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_orders", Long::class.java)).isZero()
    }

    @Test
    fun `상품 결제 링크가 없으면 503이며 주문을 만들지 않는다`() {
        val owner = user()
        for (productId in listOf("if_10000", "if_30000", "if_50000", "if_100000")) {
            client.post().uri("/api/v1/users/me/credits/orders").header("Authorization", bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to productId))
                .exchange().expectStatus().isEqualTo(503)
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_orders", Long::class.java)).isZero()
    }

    @Test
    fun `게스트는 주문 생성과 조회 모두 401이다`() {
        client.post().uri("/api/v1/users/me/credits/orders")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to "if_2000"))
            .exchange().expectStatus().isUnauthorized
        client.get().uri("/api/v1/users/me/credits/orders/${UUID.randomUUID()}")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `본인 주문은 200이고 타인과 없는 주문은 404다`() {
        val owner = user()
        val id = create(owner, "if_2000", "https://pay.example.test/2000?ref=")
        client.get().uri("/api/v1/users/me/credits/orders/$id").header("Authorization", bearer(owner))
            .exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.orderId").isEqualTo(id)
            .jsonPath("$.productId").isEqualTo("if_2000")
            .jsonPath("$.status").isEqualTo("PENDING")
            .jsonPath("$.totalCredits").isEqualTo(2000)
            .jsonPath("$.createdAt").isNotEmpty
            .jsonPath("$.completedAt").isEmpty
            .jsonPath("$.id").doesNotExist()
            .jsonPath("$.userId").doesNotExist()
        client.get().uri("/api/v1/users/me/credits/orders/$id").header("Authorization", bearer(user()))
            .exchange().expectStatus().isNotFound
        client.get().uri("/api/v1/users/me/credits/orders/${UUID.randomUUID()}").header("Authorization", bearer(owner))
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `정지와 탈퇴 계정은 주문 생성 공통 게이트를 통과하지 못한다`() {
        for ((status, expected) in listOf(UserStatus.SUSPENDED to 403, UserStatus.DELETED to 401)) {
            val user = users.save(User(nickname = "주문 제한", status = status))
            client.post().uri("/api/v1/users/me/credits/orders").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to "if_2000"))
                .exchange().expectStatus().isEqualTo(expected)
        }
    }

    @Test
    fun `OpenAPI는 공개 상품과 인증 주문 및 오류 바디를 구분한다`() {
        val body = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val paths = mapper.readTree(body).path("paths")
        assertThat(paths.path("/api/v1/credits/products").path("get").path("security").size()).isZero()
        val create = paths.path("/api/v1/users/me/credits/orders").path("post")
        assertThat(create.path("security")[0].has("bearerAuth")).isTrue()
        assertThat(create.path("responses").has("201")).isTrue()
        for (code in listOf("400", "401", "403", "503")) {
            assertThat(create.path("responses").path(code).path("content").path("application/json")
                .path("schema").path("\$ref").asText()).isEqualTo("#/components/schemas/ApiErrorResponse")
        }
    }

    private fun user() = users.save(User(nickname = "충전 회원"))
    private fun bearer(user: User) = "Bearer ${jwt.issueAccessToken(user.publicId)}"
    private fun create(user: User, productId: String, urlPrefix: String): String {
        val body = client.post().uri("/api/v1/users/me/credits/orders").header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to productId))
            .exchange().expectStatus().isCreated.expectBody(String::class.java).returnResult().responseBody!!
        val json = mapper.readTree(body)
        val id = json["orderId"].asText()
        assertThat(UUID.fromString(id).toString()).isEqualTo(id)
        assertThat(json["paymentUrl"].asText()).isEqualTo(urlPrefix + id)
        return id
    }
}

@ActiveProfiles("test")
@AutoConfigureRestTestClient
@ContextConfiguration(initializers = [CreditOrderPaymentTestInitializer::class])
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "manyak.payment.groble.webhook-secret=",
    ],
)
class CreditOrderUnconfiguredIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `링크가 있어도 웹훅 시크릿이 없으면 503이고 주문이 없다`() {
        cleaner.cleanAll()
        val user = users.save(User(nickname = "충전 회원"))
        client.post().uri("/api/v1/users/me/credits/orders")
            .header("Authorization", "Bearer ${jwt.issueAccessToken(user.publicId)}")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("productId" to "if_2000"))
            .exchange().expectStatus().isEqualTo(503)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_orders", Long::class.java)).isZero()
    }
}

/** 리스트 속성은 상위 소스가 전체 교체하므로 실제 yml 상품을 복사하고 링크만 바꾼다. */
class CreditOrderPaymentTestInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(context: ConfigurableApplicationContext) {
        val products = mutableMapOf<String, Any>()
        YamlPropertySourceLoader().load("credit-order-products", ClassPathResource("application.yml"))
            .forEach { source ->
                (source as EnumerablePropertySource<*>).propertyNames
                    .filter { it.startsWith("manyak.payment.groble.products[") }
                    .forEach { name -> source.getProperty(name)?.let { products[name] = it } }
            }
        products["manyak.payment.groble.products[0].payment-url"] = "https://pay.example.test/2000"
        products["manyak.payment.groble.products[1].payment-url"] = "https://pay.example.test/5000?source=manyak"
        context.environment.propertySources.addFirst(MapPropertySource("credit-order-products", products))
    }
}
