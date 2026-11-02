package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.chat.entity.*
import com.knk.manyak.chat.repository.*
import com.knk.manyak.credit.entity.*
import com.knk.manyak.credit.repository.CreditOrderRepository
import com.knk.manyak.story.entity.Story
import com.knk.manyak.story.repository.StoryRepository
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import java.time.*

@Import(AdminReadClockConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids=987654321"])
class AdminPaymentMetricsIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var orders: CreditOrderRepository
    @Autowired private lateinit var stories: StoryRepository
    @Autowired private lateinit var chats: StoryChatRepository
    @Autowired private lateinit var messages: StoryMessageRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private lateinit var admin: User
    private lateinit var token: String

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = user("지표 관리자")
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun user(name: String, joined: Instant = Instant.parse("2026-01-01T00:00:00Z")) = users.saveAndFlush(User(nickname = name, createdAt = joined))
    private fun order(user: User, at: Instant? = now, price: Long = 2000, credits: Long = 2000, product: String = "if_2000",
                      status: CreditOrderStatus = CreditOrderStatus.COMPLETED, created: Instant = now) =
        orders.saveAndFlush(CreditOrder(userId = user.id, productId = product, provider = CreditOrderProvider.GROBLE,
            status = status, priceKrw = price, creditAmount = credits, completedAt = at, createdAt = created))
    private fun message(user: User?, at: Instant = now, role: MessageRole = MessageRole.USER, deleted: Boolean = false) {
        val story = stories.saveAndFlush(Story(title = "지표 스토리"))
        val chat = chats.saveAndFlush(StoryChat(storyId = story.id, userId = user?.id, deletedAt = if (deleted) now else null))
        messages.saveAndFlush(StoryMessage(chatId = chat.id, role = role, content = "집계에 선택하지 않는 원문", messageOrder = 1, createdAt = at))
    }
    private fun response(query: String = "", auth: String? = token) = client.get().uri("/api/v1/admin/payments/metrics$query")
        .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun read(query: String = "") = mapper.readTree(response(query).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `결제 전환은 USER 활성 회원과 메시지 없는 결제 회원도 각각 세며 삭제 채팅을 포함한다`() {
        val active = (1..10).map { user("활성$it") }
        active.forEachIndexed { i, user -> message(user, deleted = i == 0) }
        message(null)
        message(user("AI만"), role = MessageRole.ASSISTANT)
        listOf(active[0], active[1], user("메시지 없는 결제")).forEach { order(it) }
        val body = read("?days=7")
        assertThat(body["activeMembers"].asLong()).isEqualTo(10)
        assertThat(body["payingMembers"].asLong()).isEqualTo(3)
        assertThat(body["conversionRate"].asDouble()).isEqualTo(0.3)
        assertThat(body["orders"].asLong()).isEqualTo(3)
        assertThat(body["amountKrw"].asLong()).isEqualTo(6000)
        assertThat(body["arppu"].asDouble()).isEqualTo(2000.0)
    }

    @Test fun `첫 결제는 기간 밖 환불 완료도 보고 완료 동률은 생성시각 대신 작은 주문 id로 정한다`() {
        val a = user("기존 결제")
        val b = user("동률 결제")
        val c = user("과거 환불 결제")
        order(a, Instant.parse("2026-10-01T00:00:00Z"))
        order(c, Instant.parse("2026-10-01T00:00:00Z"), status = CreditOrderStatus.REFUNDED)
        order(a, price = 4800)
        order(b, price = 2000, created = now)
        order(b, price = 4800, created = now.minusSeconds(86400))
        order(c, price = 4800, status = CreditOrderStatus.REFUNDED)
        order(c, at = null, status = CreditOrderStatus.PENDING)
        val body = read("?from=2026-10-21&to=2026-10-21")
        assertThat(body["orders"].asLong()).isEqualTo(4)
        assertThat(body["amountKrw"].asLong()).isEqualTo(16400)
        assertThat(body["firstTime"]["orders"].asLong()).isEqualTo(1)
        assertThat(body["firstTime"]["amountKrw"].asLong()).isEqualTo(2000)
        assertThat(body["repeat"]["orders"].asLong()).isEqualTo(3)
        assertThat(body["repeat"]["amountKrw"].asLong()).isEqualTo(14400)
        assertThat(body["refundedOrders"].asLong()).isEqualTo(1)
        assertThat(body["refundRate"].asDouble()).isEqualTo(0.25)
        assertThat(body["conversionRate"].isNull).isTrue()
    }

    @Test fun `상품 매출은 주문 스냅샷을 합산하고 금액 내림차순 동률 상품 id 오름차순이다`() {
        val member = user("상품 결제")
        repeat(2) { order(member, price = 4800, credits = 5000, product = "if_5000") }
        for (product in listOf("z-tie", "if_2000", "a-tie")) order(member, product = product)
        val products = read("?days=7")["products"].toList()
        assertThat(products.map { it["productId"].asText() }).containsExactly("if_5000", "a-tie", "if_2000", "z-tie")
        assertThat(products[0]["orders"].asLong()).isEqualTo(2)
        assertThat(products[0]["amountKrw"].asLong()).isEqualTo(9600)
        assertThat(products[0]["creditAmount"].asLong()).isEqualTo(10000)
    }

    @Test fun `첫 결제 일수는 KST 날짜 차이로 모든 구간 경계를 세고 항상 다섯 항목을 반환한다`() {
        val date = LocalDate.parse("2026-10-20")
        val zone = ZoneId.of("Asia/Seoul")
        for (days in listOf(0L, 1L, 3L, 4L, 7L, 8L, 30L, 31L)) {
            val joined = date.minusDays(days).atTime(0, 30).atZone(zone).toInstant()
            order(user("일수$days", joined), at = date.atTime(23, 0).atZone(zone).toInstant())
        }
        val buckets = read("?from=2026-10-20&to=2026-10-20")["daysToFirstPayment"].toList()
        assertThat(buckets.map { it["bucket"].asText() }).containsExactly("0", "1~3", "4~7", "8~30", "31+")
        assertThat(buckets.map { it["members"].asLong() }).containsExactly(1, 2, 2, 2, 1)
    }

    @Test fun `결제 없는 응답은 분모 영 비율 null과 빈 상품 및 다섯 영 구간을 반환한다`() {
        val body = read()
        assertThat(body["from"].asText()).isEqualTo("2026-09-22")
        assertThat(body["to"].asText()).isEqualTo("2026-10-21")
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        for (key in listOf("activeMembers", "payingMembers", "orders", "amountKrw", "refundedOrders")) assertThat(body[key].asLong()).isZero()
        for (key in listOf("conversionRate", "arppu", "refundRate")) assertThat(body[key].isNull).isTrue()
        assertThat(body["products"].size()).isZero()
        assertThat(body["daysToFirstPayment"].size()).isEqualTo(5)
        body["daysToFirstPayment"].forEach { assertThat(it["members"].asLong()).isZero() }
    }

    @Test fun `결제 지표는 팀 계정의 메시지 주문 상품 환불과 첫 결제를 모든 집계에서 제외한다`() {
        val seeded = user("제외 팀", now.minusSeconds(86400))
        jdbc.update("UPDATE users SET id=987654321 WHERE id=?", seeded.id)
        val team = users.findById(987654321).orElseThrow()
        val ordinary = user("일반")
        message(team); message(ordinary)
        order(team, product = "TEAM_ONLY", price = 5000, status = CreditOrderStatus.REFUNDED)
        order(ordinary)
        val body = read("?days=7")
        assertThat(body["activeMembers"].asLong()).isEqualTo(1)
        assertThat(body["payingMembers"].asLong()).isEqualTo(1)
        assertThat(body["orders"].asLong()).isEqualTo(1)
        assertThat(body["amountKrw"].asLong()).isEqualTo(2000)
        assertThat(body["refundedOrders"].asLong()).isZero()
        assertThat(body["firstTime"]["orders"].asLong()).isEqualTo(1)
        assertThat(body["products"].single()["productId"].asText()).isEqualTo("if_2000")
        assertThat(body["daysToFirstPayment"].sumOf { it["members"].asLong() }).isEqualTo(1)
    }

    @Test fun `결제 지표는 차트와 같은 기간 검증과 관리자 인증을 적용한다`() {
        for (days in listOf(7, 30, 90)) read("?days=$days")
        read("?from=2025-10-21&to=2026-10-21")
        for (query in listOf("days=10", "days=", "days=bad", "from=2026-10-21", "to=2026-10-21", "from=bad&to=bad",
            "from=2026-10-22&to=2026-10-22", "from=2026-10-21&to=2026-10-20", "from=2025-10-20&to=2026-10-21",
            "days=7&from=2026-10-20&to=2026-10-21")) response("?$query").expectStatus().isBadRequest
        response(auth = null).expectStatus().isUnauthorized
        response(auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
    }

    @Test fun `결제 지표 기간은 KST 자정부터 마지막 마이크로초까지 포함하고 현재 시각 이후는 제외한다`() {
        val member = user("경계")
        for (at in listOf("2026-10-19T14:59:59.999999Z", "2026-10-19T15:00:00Z", "2026-10-20T14:59:59.999999Z", "2026-10-20T15:00:00Z"))
            order(member, Instant.parse(at))
        order(member, now.plusSeconds(1))
        message(member, Instant.parse("2026-10-20T14:59:59.999999Z"))
        val past = read("?from=2026-10-20&to=2026-10-20")
        assertThat(past["orders"].asLong()).isEqualTo(2)
        assertThat(past["activeMembers"].asLong()).isEqualTo(1)
        assertThat(read("?from=2026-10-21&to=2026-10-21")["orders"].asLong()).isEqualTo(1)
    }
}
