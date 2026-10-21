package com.knk.manyak.credit.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.credit.service.CreditPolicyService
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@org.springframework.context.annotation.Import(com.knk.manyak.credit.support.H2CreditAdminLocksConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminCreditIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var policies: CreditPolicyService
    private val mapper = ObjectMapper()
    private lateinit var admin: User
    private lateinit var member: User
    private lateinit var token: String
    private val root = "/api/v1/admin/credits"

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        policies.refresh()
        admin = users.save(User(nickname = "관리자"))
        member = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }

    private fun body(type: String = "GRANT", amount: Long = 5000, key: String = UUID.randomUUID().toString(), days: Int? = null) =
        mutableMapOf<String, Any>("memberId" to member.publicId.toString(), "type" to type, "amount" to amount,
            "idempotencyKey" to key, "reason" to "장애 보상").apply { if (days != null) put("expiresInDays", days) }
    private fun post(body: Map<String, Any>, auth: String? = token) = client.post().uri("$root/adjustments")
        .contentType(MediaType.APPLICATION_JSON).apply { auth?.let { header("Authorization", "Bearer $it") } }.body(body).exchange()
    private fun result(body: Map<String, Any>): JsonNode = mapper.readTree(post(body).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
    private fun count(table: String) = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java)!!
    private fun policy(amount: Long, until: String? = null) = mutableMapOf<String, Any>("amount" to amount, "reason" to "이벤트").apply {
        until?.let { put("effectiveUntil", it) }
    }
    private fun put(key: String, data: Map<String, Any>) = client.put().uri("$root/policies/$key")
        .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).body(data).exchange()
    private fun delete(key: String) = client.method(org.springframework.http.HttpMethod.DELETE).uri("$root/policies/$key")
        .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).body(mapOf("reason" to "이벤트 종료")).exchange()

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var auditRepository: com.knk.manyak.admin.repository.AdminAuditLogRepository

    @Test fun `감사 저장 실패는 조정 원장 로트 잔액과 정책 변경을 모두 롤백한다`() {
        result(body(amount = 100))
        put("attendance_reward", policy(300)).expectStatus().isOk
        val placeholder = com.knk.manyak.admin.entity.AdminAuditLog(
            adminUserId = admin.id, category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
            action = com.knk.manyak.admin.entity.AdminAuditAction.CREDIT_GRANTED,
            targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.MEMBER,
            targetId = member.publicId.toString(), requestId = "matcher")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected audit failure"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java) ?: placeholder)
        post(body(key = "failed-grant")).expectStatus().is5xxServerError
        post(body("REVOKE", 100, "failed-revoke")).expectStatus().is5xxServerError
        put("attendance_reward", policy(500)).expectStatus().is5xxServerError
        put("chat_turn_cost", policy(50)).expectStatus().is5xxServerError
        delete("attendance_reward").expectStatus().is5xxServerError
        assertThat(count("credit_admin_adjustments")).isEqualTo(1)
        assertThat(count("credit_transactions")).isEqualTo(1)
        assertThat(count("credit_lots")).isEqualTo(1)
        assertThat(count("admin_audit_logs")).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallets WHERE user_id = ?", Long::class.java, member.id)).isEqualTo(100)
        assertThat(jdbc.queryForObject("SELECT remaining FROM credit_lots", Long::class.java)).isEqualTo(100)
        assertThat(jdbc.queryForObject("SELECT amount FROM credit_policies", Long::class.java)).isEqualTo(300)
    }

    @Test fun `정책 7종은 각 하한과 공통 상한을 저장 전에 검사한다`() {
        for (key in com.knk.manyak.credit.service.CreditPolicyKey.entries) {
            put(key.storageKey, policy(key.minimumAmount - 1)).expectStatus().isBadRequest
            put(key.storageKey, policy(10001)).expectStatus().isBadRequest
            assertThat(count("credit_policies")).isZero()
            put(key.storageKey, policy(key.minimumAmount)).expectStatus().isOk
            put(key.storageKey, policy(10000)).expectStatus().isOk
            delete(key.storageKey).expectStatus().isOk
        }
    }

    @Test fun `만료된 로트는 회수 가능액에서 제외하고 만료 원장으로 정리한다`() {
        result(body(amount = 40))
        jdbc.update("UPDATE credit_lots SET expires_at = ?", java.time.OffsetDateTime.now().minusDays(1))
        result(body(amount = 10))
        val response = result(body("REVOKE", 30))
        assertThat(response["appliedAmount"].asLong()).isEqualTo(10)
        assertThat(response["shortfall"].asLong()).isEqualTo(20)
        assertThat(response["balance"].asLong()).isZero()
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM credit_transactions", Long::class.java)).isZero()
    }

    @Test fun `지급은 처음 결과와 유효기간 및 감사 처리자를 보존한다`() {
        result(body(amount = 1230))
        val request = body(key = "grant-one")
        val first = result(request)
        assertThat(first["balance"].asLong()).isEqualTo(6230)
        assertThat(first["appliedAmount"].asLong()).isEqualTo(5000)
        val expiry = Instant.parse(first["expiresAt"].asText())
        assertThat(Duration.between(Instant.now(), expiry).toDays()).isEqualTo(29)
        result(body(amount = 10))
        val again = result(request)
        assertThat(again["duplicated"].asBoolean()).isTrue()
        assertThat(again["balance"].asLong()).isEqualTo(6230)
        assertThat(again["expiresAt"]).isEqualTo(first["expiresAt"])
        assertThat(count("credit_admin_adjustments")).isEqualTo(3)
        assertThat(count("admin_audit_logs")).isEqualTo(3)
        val audit = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id LIMIT 1")
        assertThat(audit["admin_user_id"]).isEqualTo(admin.id)
        assertThat(audit["target_id"]).isEqualTo(member.publicId.toString())
        assertThat(audit["action"]).isEqualTo("CREDIT_GRANTED")
        val after = mapper.readTree(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs ORDER BY id LIMIT 1", String::class.java)!!)
        assertThat(after["expiresInDays"].asInt()).isEqualTo(30)
        assertThat(after.hasNonNull("expiresAt")).isTrue()
        assertThat(after["appliedAmount"].asLong()).isEqualTo(1230)
    }

    @Test fun `지급 유효기간 경계 1일 90일 365일`() {
        for (days in listOf(1, 90, 365)) {
            val before = Instant.now()
            val response = result(body(days = days))
            val expiry = Instant.parse(response["expiresAt"].asText())
            assertThat(expiry).isBetween(before.plusSeconds(days * 86400L), Instant.now().plusSeconds(days * 86400L))
            val lotExpiry = jdbc.queryForObject("SELECT expires_at FROM credit_lots ORDER BY id DESC LIMIT 1", java.time.OffsetDateTime::class.java)!!.toInstant()
            assertThat(lotExpiry).isEqualTo(expiry)
        }
    }

    @Test fun `회수는 만료 임박 순으로 잔액까지만 적용하고 부족분을 감사한다`() {
        result(body(amount = 1230, days = 90))
        result(body(amount = 5000, days = 1))
        val first = result(body("REVOKE", 1000, days = 10))
        assertThat(first["balance"].asLong()).isEqualTo(5230)
        assertThat(first["expiresAt"].isNull).isTrue()
        assertThat(jdbc.queryForList("SELECT remaining FROM credit_lots ORDER BY id", Long::class.java)).containsExactly(1230L, 4000L)
        val last = result(body("REVOKE", 10000))
        assertThat(last["appliedAmount"].asLong()).isEqualTo(5230)
        assertThat(last["shortfall"].asLong()).isEqualTo(4770)
        assertThat(last["balance"].asLong()).isZero()
        val after = mapper.readTree(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)!!)
        assertThat(after["shortfall"].asLong()).isEqualTo(4770)
        assertThat(jdbc.queryForObject("SELECT amount FROM credit_transactions ORDER BY id DESC LIMIT 1", Long::class.java)).isEqualTo(-5230)
    }

    @Test fun `0원 회수 뒤 입금해도 같은 키는 처음의 0원 결과를 반환한다`() {
        val request = body("REVOKE", 100, "zero-revoke")
        val first = result(request)
        assertThat(first["appliedAmount"].asLong()).isZero()
        assertThat(first["shortfall"].asLong()).isEqualTo(100)
        assertThat(count("credit_transactions")).isZero()
        assertThat(count("credit_admin_adjustments")).isEqualTo(1)
        assertThat(count("admin_audit_logs")).isEqualTo(1)
        result(body(amount = 500))
        val again = result(request)
        assertThat(again["duplicated"].asBoolean()).isTrue()
        assertThat(again["balance"].asLong()).isZero()
        assertThat(jdbc.queryForObject("SELECT balance FROM credit_wallets WHERE user_id = ?", Long::class.java, member.id)).isEqualTo(500)
        assertThat(count("admin_audit_logs")).isEqualTo(2)
    }

    @Test fun `같은 키에 회원 종류 금액 유효기간을 바꾸면 각각 409다`() {
        val request = body(key = "same-key")
        result(request)
        val other = users.save(User(nickname = "다른회원"))
        for ((field, value) in listOf("memberId" to other.publicId.toString(), "type" to "REVOKE", "amount" to 3L, "expiresInDays" to 90)) {
            post(request.toMutableMap().apply { put(field, value) }).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_CONFLICT")
        }
        assertThat(count("credit_transactions")).isEqualTo(1)
    }

    @Test fun `같은 키 동시 요청은 한번 적용하고 한쪽에 중복 결과를 준다`() {
        val request = body(key = "concurrent-key")
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit(Callable { gate.await(); result(request) }) }
            gate.countDown()
            val responses = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(responses.map { it["duplicated"].asBoolean() }).containsExactlyInAnyOrder(false, true)
            assertThat(count("credit_transactions")).isEqualTo(1)
            assertThat(count("credit_admin_adjustments")).isEqualTo(1)
            assertThat(count("admin_audit_logs")).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `서로 다른 회원도 같은 키로 동시에 요청하면 한번만 적용된다`() {
        val other = users.save(User(nickname = "경쟁회원"))
        val requests = listOf(body(key = "cross-member-key"), body(key = "cross-member-key").apply { put("memberId", other.publicId.toString()) })
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = requests.map { request -> pool.submit(Callable {
                gate.await()
                post(request).expectBody(String::class.java).returnResult()
            }) }
            gate.countDown()
            val responses = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(responses.map { it.status.value() }).containsExactlyInAnyOrder(200, 409)
            val conflict = responses.single { it.status.value() == 409 }
            assertThat(mapper.readTree(conflict.responseBody!!)["code"].asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT")
            assertThat(count("credit_transactions")).isEqualTo(1)
            assertThat(count("credit_admin_adjustments")).isEqualTo(1)
            assertThat(count("admin_audit_logs")).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `조정 입력과 대상 및 인증 오류`() {
        for ((field, value) in listOf("amount" to 0, "amount" to 100001, "expiresInDays" to 0, "expiresInDays" to 366,
            "idempotencyKey" to "x".repeat(65), "idempotencyKey" to "has space", "type" to "SET", "reason" to " ", "memberId" to "bad")) {
            post(body().apply { put(field, value) }).expectStatus().isBadRequest
        }
        for (field in listOf("memberId", "type", "amount", "idempotencyKey", "reason")) {
            post(body().apply { remove(field) }).expectStatus().isBadRequest
        }
        post(body().apply { put("memberId", UUID.randomUUID().toString()) }).expectStatus().isNotFound
        jdbc.update("UPDATE users SET status = 'DELETED' WHERE id = ?", member.id)
        post(body()).expectStatus().isEqualTo(409)
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", member.id)
        post(body()).expectStatus().isOk
        post(body(), null).expectStatus().isUnauthorized
        post(body(), jwt.issueAccessToken(admin.publicId)).expectStatus().isUnauthorized
        jdbc.update("DELETE FROM admin_accounts WHERE user_id = ?", admin.id)
        post(body()).expectStatus().isForbidden
    }

    @Test fun `관리자 지급 회수 이용내역은 획득 소멸이며 제목은 없다`() {
        result(body())
        result(body("REVOKE", 1))
        for ((type, reason) in listOf("EARN" to "ADMIN_GRANT", "EXPIRE" to "ADMIN_REVOKE")) {
            client.get().uri("/api/v1/users/me/credits/transactions?type=$type")
                .header("Authorization", "Bearer ${jwt.issueAccessToken(member.publicId)}").exchange().expectStatus().isOk
                .expectBody().jsonPath("$.items[0].reason").isEqualTo(reason).jsonPath("$.items[0].title").isEmpty()
        }
    }

    @Test fun `정책 조회 기록 덮어쓰기 갱신 삭제 및 전후 감사`() {
        put("attendance_reward", policy(500, Instant.now().plusSeconds(3600).toString())).expectStatus().isOk
        policies.refresh()
        client.get().uri("$root/policies").header("Authorization", "Bearer $token").exchange().expectStatus().isOk
            .expectBody().jsonPath("$.items.length()").isEqualTo(7)
            .jsonPath("$.items[0].policyKey").isEqualTo("attendance_reward")
            .jsonPath("$.items[0].override.amount").isEqualTo(500).jsonPath("$.items[0].effectiveAmount").isEqualTo(500)
        put("attendance_reward", policy(300)).expectStatus().isOk
        assertThat(count("credit_policies")).isEqualTo(1)
        policies.refresh()
        client.get().uri("/api/v1/credits/policies").exchange().expectStatus().isOk.expectBody().jsonPath("$.attendanceReward").isEqualTo(300)
        val audit = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id DESC LIMIT 1")
        assertThat(audit["action"]).isEqualTo("CREDIT_POLICY_UPSERTED")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)!!)["amount"].asLong()).isEqualTo(500)
        delete("attendance_reward").expectStatus().isOk.expectBody().jsonPath("$.override").isEmpty()
        policies.refresh()
        client.get().uri("/api/v1/credits/policies").exchange().expectStatus().isOk.expectBody().jsonPath("$.attendanceReward").isEqualTo(250)
        assertThat(jdbc.queryForObject("SELECT action FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)).isEqualTo("CREDIT_POLICY_DELETED")
        delete("attendance_reward").expectStatus().isNotFound
        for (data in listOf(policy(0), policy(10001), policy(300, Instant.now().minusSeconds(1).toString()))) {
            put("attendance_reward", data).expectStatus().isBadRequest
        }
        put("unknown", policy(1)).expectStatus().isNotFound
        put("invite_monthly_cap", mapOf("reason" to "금액 누락")).expectStatus().isBadRequest
        put("attendance_reward", mapOf("amount" to 100)).expectStatus().isBadRequest
        client.method(org.springframework.http.HttpMethod.DELETE).uri("$root/policies/attendance_reward")
            .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).body("{}")
            .exchange().expectStatus().isBadRequest
        for (key in listOf("invite_monthly_cap", "chat_image_cost")) put(key, policy(0)).expectStatus().isOk
    }
}
