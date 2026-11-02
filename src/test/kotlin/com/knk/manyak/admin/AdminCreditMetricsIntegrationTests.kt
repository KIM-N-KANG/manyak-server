package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.credit.entity.*
import com.knk.manyak.credit.repository.*
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
import java.time.Instant
import java.time.temporal.ChronoUnit

@Import(AdminReadClockConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids=987654321"])
class AdminCreditMetricsIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var transactions: CreditTransactionRepository
    @Autowired private lateinit var lots: CreditLotRepository
    @Autowired private lateinit var wallets: CreditWalletRepository
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private lateinit var admin: User
    private lateinit var token: String

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = user("경제 관리자")
        jdbc.update("INSERT INTO admin_accounts(user_id,granted_at) VALUES (?,CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun user(name: String, status: UserStatus = UserStatus.ACTIVE, deleted: Instant? = null) =
        users.saveAndFlush(User(nickname = name, status = status, deletedAt = deleted))
    private fun ledger(owner: User, amount: Long, reason: CreditReason, at: Instant = now) =
        transactions.saveAndFlush(CreditTransaction(userId = owner.id, amount = amount, reason = reason, createdAt = at))
    private fun lot(owner: User, amount: Long, reason: CreditReason? = null, expires: Instant? = null, remaining: Long = amount) =
        lots.saveAndFlush(CreditLot(userId = owner.id,
            transactionId = reason?.let { ledger(owner, amount, it, now.minus(100, ChronoUnit.DAYS)).id },
            originalAmount = amount, remaining = remaining, expiresAt = expires, createdAt = now.minus(100, ChronoUnit.DAYS)))
    private fun response(query: String = "", auth: String? = token) = client.get().uri("/api/v1/admin/credits/metrics$query")
        .apply { auth?.let { header("Authorization", "Bearer $it") } }.exchange()
    private fun read(query: String = "") = mapper.readTree(response(query).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)

    @Test fun `이프 적립 소모는 차트 정의와 같고 만료 환불 회수와 반대 부호를 제외한다`() {
        ledger(admin, 1000, CreditReason.SIGNUP_REWARD)
        ledger(admin, -650, CreditReason.CHAT_TURN)
        ledger(admin, -30, CreditReason.EXPIRE)
        ledger(admin, 20, CreditReason.REFUND)
        ledger(admin, -100, CreditReason.PURCHASE_REVERSAL)
        ledger(admin, -10, CreditReason.ADMIN_REVOKE)
        ledger(admin, -11, CreditReason.PURCHASE)
        ledger(admin, 9, CreditReason.CHAT_TURN)
        val body = read("?days=7")
        assertThat(body["granted"].asLong()).isEqualTo(1000)
        assertThat(body["spent"].asLong()).isEqualTo(650)
        assertThat(body["sinkRate"].asDouble()).isEqualTo(0.65)
        val chart = mapper.readTree(client.get().uri("/api/v1/admin/dashboard/charts?days=7").header("Authorization", "Bearer $token").exchange()
            .expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(chart["daily"].sumOf { it["granted"].asLong() }).isEqualTo(body["granted"].asLong())
        assertThat(chart["daily"].sumOf { it["spent"].asLong() }).isEqualTo(body["spent"].asLong())
    }

    @Test fun `사유별 합은 다섯 적립과 세 소모를 고정 순서로 반환하고 없는 사유는 영이다`() {
        val grants = listOf(CreditReason.SIGNUP_REWARD, CreditReason.INVITE_REWARD, CreditReason.ATTENDANCE_REWARD, CreditReason.PURCHASE, CreditReason.ADMIN_GRANT)
        val spends = listOf(CreditReason.STORY_CREATION, CreditReason.CHAT_TURN, CreditReason.CHAT_IMAGE)
        val empty = read()
        assertThat(empty["grantedByReason"].toList().map { it["reason"].asText() }).containsExactlyElementsOf(grants.map { it.name })
        assertThat(empty["spentByReason"].toList().map { it["reason"].asText() }).containsExactlyElementsOf(spends.map { it.name })
        empty["grantedByReason"].forEach { assertThat(it["amount"].asLong()).isZero() }
        empty["spentByReason"].forEach { assertThat(it["amount"].asLong()).isZero() }
        grants.zip(listOf(100L, 200L, 250L, 5000L, 50L)).forEach { (reason, value) -> ledger(admin, value, reason) }
        spends.zip(listOf(-10L, -20L, -30L)).forEach { (reason, value) -> ledger(admin, value, reason) }
        val body = read("?days=7")
        assertThat(body["granted"].asLong()).isEqualTo(5600)
        assertThat(body["spent"].asLong()).isEqualTo(60)
        assertThat(body["grantedByReason"].toList().map { it["amount"].asLong() }).containsExactly(100, 200, 250, 5000, 50)
        assertThat(body["spentByReason"].toList().map { it["amount"].asLong() }).containsExactly(10, 20, 30)
    }

    @Test fun `유효 잔액은 로트 잔여만 읽고 구매 보상 승계 구성과 로트 없는 회원을 함께 센다`() {
        lot(admin, 1000, CreditReason.PURCHASE, now.plus(365 * 5L, ChronoUnit.DAYS), remaining = 300)
        lot(admin, 250, CreditReason.ATTENDANCE_REWARD, now.plus(20, ChronoUnit.DAYS), remaining = 50)
        lot(admin, 100)
        val expired = lot(admin, 40, CreditReason.REFUND, now.minusSeconds(1))
        lot(admin, 10, CreditReason.ADMIN_GRANT, remaining = 0)
        user("로트 없음")
        lot(user("탈퇴", UserStatus.DELETED), 500)
        lot(user("탈퇴 시각만", deleted = now), 600)
        wallets.saveAndFlush(CreditWallet(userId = admin.id, balance = 99999))
        val before = transactions.count()
        val b = read()["balances"]
        assertThat(b["members"].asLong()).isEqualTo(2)
        assertThat(b["total"].asLong()).isEqualTo(450)
        assertThat(b["purchased"].asLong()).isEqualTo(300)
        assertThat(b["rewarded"].asLong()).isEqualTo(50)
        assertThat(b["legacy"].asLong()).isEqualTo(100)
        assertThat(b["expiringIn30Days"].asLong()).isEqualTo(50)
        assertThat(b["buckets"].toList().map { it["members"].asLong() }).containsExactly(1, 0, 1, 0, 0)
        assertThat(lots.findById(expired.id).orElseThrow().remaining).isEqualTo(40)
        assertThat(transactions.count()).isEqualTo(before)
    }

    @Test fun `잔액 구간 경계는 영부터 2000 이상까지 고정 다섯 항목이다`() {
        for (value in listOf(99L, 100L, 499L, 500L, 1999L, 2000L)) lot(user("잔액$value"), value)
        val buckets = read()["balances"]["buckets"].toList()
        assertThat(buckets.map { it["bucket"].asText() }).containsExactly("0", "1~99", "100~499", "500~1999", "2000+")
        assertThat(buckets.map { it["members"].asLong() }).containsExactly(1, 1, 2, 2, 1)
    }

    @Test fun `현재 잔액은 조회 기간과 무관하고 만료 경계는 지금 제외와 정확히 30일 뒤 포함이다`() {
        lot(admin, 1, CreditReason.REFUND, now.minusNanos(1000))
        lot(admin, 2, CreditReason.REFUND, now)
        lot(admin, 3, CreditReason.REFUND, now.plusNanos(1000))
        lot(admin, 5, CreditReason.ADMIN_GRANT, now.plus(30, ChronoUnit.DAYS))
        lot(admin, 7, CreditReason.SIGNUP_REWARD, now.plus(30, ChronoUnit.DAYS).plusNanos(1000))
        lot(admin, 11, CreditReason.PURCHASE)
        lot(admin, 13) // 무기한 승계
        val current = read("?days=7")["balances"]
        assertThat(current["total"].asLong()).isEqualTo(39)
        assertThat(current["expiringIn30Days"].asLong()).isEqualTo(8)
        assertThat(current["purchased"].asLong()).isEqualTo(11)
        assertThat(current["rewarded"].asLong()).isEqualTo(15)
        assertThat(current["legacy"].asLong()).isEqualTo(13)
        assertThat(read("?from=2020-01-01&to=2020-01-01")["balances"]).isEqualTo(current)
    }

    @Test fun `잔액은 정지 회원을 포함하고 원장 집계의 탈퇴 회원 기록은 차트처럼 유지한다`() {
        lot(user("정지", UserStatus.SUSPENDED), 10)
        ledger(user("탈퇴 원장", UserStatus.DELETED), 20, CreditReason.ADMIN_GRANT)
        val body = read()
        assertThat(body["granted"].asLong()).isEqualTo(20)
        assertThat(body["balances"]["members"].asLong()).isEqualTo(2)
        assertThat(body["balances"]["total"].asLong()).isEqualTo(10)
    }

    @Test fun `팀 계정은 원장과 로트 및 영 구간 회원 수에서도 제외한다`() {
        val seeded = user("제외 팀")
        jdbc.update("UPDATE users SET id=987654321 WHERE id=?", seeded.id)
        val team = users.findById(987654321).orElseThrow()
        ledger(team, 5000, CreditReason.PURCHASE)
        ledger(team, -900, CreditReason.CHAT_IMAGE)
        lot(team, 5000, CreditReason.PURCHASE, now.plus(1, ChronoUnit.DAYS))
        ledger(admin, 250, CreditReason.ATTENDANCE_REWARD)
        ledger(admin, -20, CreditReason.CHAT_TURN)
        val body = read()
        assertThat(body["granted"].asLong()).isEqualTo(250)
        assertThat(body["spent"].asLong()).isEqualTo(20)
        assertThat(body["balances"]["members"].asLong()).isEqualTo(1)
        assertThat(body["balances"]["total"].asLong()).isZero()
        assertThat(body["balances"]["buckets"][0]["members"].asLong()).isEqualTo(1)
    }

    @Test fun `적립 영이면 싱크 비율은 null이고 기간 검증과 관리자 인증은 차트와 같다`() {
        ledger(admin, -20, CreditReason.CHAT_TURN)
        val body = read()
        assertThat(body["sinkRate"].isNull).isTrue()
        assertThat(body["from"].asText()).isEqualTo("2026-09-22")
        assertThat(body["to"].asText()).isEqualTo("2026-10-21")
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        for (days in listOf(7, 30, 90)) read("?days=$days")
        read("?from=2025-10-21&to=2026-10-21")
        for (query in listOf("days=10", "days=", "days=bad", "from=2026-10-21", "to=2026-10-21", "from=bad&to=bad",
            "from=2026-10-22&to=2026-10-22", "from=2026-10-21&to=2026-10-20", "from=2025-10-20&to=2026-10-21",
            "days=7&from=2026-10-20&to=2026-10-21")) response("?$query").expectStatus().isBadRequest
        response(auth = null).expectStatus().isUnauthorized
        response(auth = jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
    }

    @Test fun `기간 원장은 KST 첫 자정과 마지막 마이크로초를 포함하고 미래 기록은 제외한다`() {
        for (at in listOf("2026-10-19T14:59:59.999999Z", "2026-10-19T15:00:00Z", "2026-10-20T14:59:59.999999Z", "2026-10-20T15:00:00Z"))
            ledger(admin, 10, CreditReason.ADMIN_GRANT, Instant.parse(at))
        ledger(admin, 1000, CreditReason.ADMIN_GRANT, now.plusSeconds(1))
        assertThat(read("?from=2026-10-20&to=2026-10-20")["granted"].asLong()).isEqualTo(20)
        assertThat(read("?from=2026-10-21&to=2026-10-21")["granted"].asLong()).isEqualTo(10)
    }
}
