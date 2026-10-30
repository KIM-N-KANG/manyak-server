package com.knk.manyak.chat.controller

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import java.time.Instant

@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatModelPromotionIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var models: com.knk.manyak.chat.repository.ChatModelRepository
    @Autowired private lateinit var trials: com.knk.manyak.credit.service.GuestTrialLimitService
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var audits: com.knk.manyak.admin.repository.AdminAuditLogRepository
    private lateinit var token: String
    private lateinit var memberToken: String
    private lateinit var member: User
    private val root = "/api/v1/admin/chat-models"
    private val start = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    private val end = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        val admin = users.save(User(nickname = "관리자"))
        member = users.save(User(nickname = "회원"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
        memberToken = jwt.issueAccessToken(member.publicId)
    }
    private fun body(cost: Long = 0, starts: String = start, ends: String = end) =
        mapOf("turnCost" to cost, "startsAt" to starts, "endsAt" to ends, "reason" to "이벤트")
    private fun put(code: String = "LITE", data: Map<String, Any> = body()) = client.put().uri("$root/$code/promotion")
        .header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).body(data).exchange()
    private fun delete(code: String = "LITE", data: Map<String, Any> = mapOf("reason" to "종료")) =
        client.method(HttpMethod.DELETE).uri("$root/$code/promotion").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(data).exchange()

    @Test fun `관리자 설정 덮어쓰기 해제는 가격과 감사 이력을 함께 저장한다`() {
        put().expectStatus().isOk.expectBody().jsonPath("$.turnCost").isEqualTo(20)
            .jsonPath("$.promotion.status").isEqualTo("ACTIVE")
        put(data = body(10)).expectStatus().isOk.expectBody().jsonPath("$.promotion.turnCost").isEqualTo(10)
        delete().expectStatus().isOk.expectBody().jsonPath("$.promotion").isEmpty
        delete().expectStatus().isNotFound
        delete("MISSING").expectStatus().isNotFound
        val rows = jdbc.queryForList("SELECT action, target_type, target_id, before_state, after_state FROM admin_audit_logs ORDER BY id")
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it["action"] }).containsExactly("CHAT_MODEL_PROMOTION_UPSERTED", "CHAT_MODEL_PROMOTION_UPSERTED", "CHAT_MODEL_PROMOTION_DELETED")
        assertThat(rows.map { it["target_type"] }).containsOnly("CHAT_MODEL")
        assertThat(rows.map { it["target_id"] }).containsOnly("LITE")
        assertThat(rows.first()["before_state"]).isNull()
        assertThat(rows.last()["after_state"]).isNull()
    }

    @Test fun `관리자 검증은 범위 시각 필수값 사유와 없는 모델을 거부한다`() {
        for (data in listOf(body(-1), body(10001), body(starts = end), body(starts = end, ends = start),
            body(ends = Instant.now().minusSeconds(10).toString()), body() - "reason", body() - "turnCost",
            body() - "startsAt", body() - "endsAt", body() + ("reason" to " "), body() + ("reason" to "a".repeat(501)))) {
            put(data = data).expectStatus().isBadRequest
        }
        put("MISSING").expectStatus().isNotFound
        delete(data = emptyMap()).expectStatus().isBadRequest
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `관리자는 비활성 모델과 예약 종료 상태를 노출 순서로 조회한다`() {
        jdbc.update("UPDATE chat_models SET is_active = false WHERE code = 'LITE'")
        put(data = body(starts = end, ends = Instant.now().plusSeconds(7200).toString())).expectStatus().isOk
        client.get().uri(root).header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].code").isEqualTo("LITE").jsonPath("$.items[0].isActive").isEqualTo(false)
            .jsonPath("$.items[0].promotion.status").isEqualTo("SCHEDULED")
        jdbc.update("UPDATE chat_models SET promo_starts_at = ?, promo_ends_at = ? WHERE code = 'LITE'",
            java.time.OffsetDateTime.now().minusHours(2), java.time.OffsetDateTime.now().minusHours(1))
        client.get().uri(root).header("Authorization", "Bearer $token").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.items[0].promotion.status").isEqualTo("ENDED")
    }

    @Test fun `관리자 경로는 미인증과 앱 토큰을 거절한다`() {
        client.get().uri(root).exchange().expectStatus().isUnauthorized
        client.get().uri(root).header("Authorization", "Bearer $memberToken").exchange().expectStatus().isUnauthorized
        client.put().uri("$root/LITE/promotion").header("Authorization", "Bearer $memberToken")
            .contentType(MediaType.APPLICATION_JSON).body(body()).exchange().expectStatus().isUnauthorized
    }

    @Test fun `감사 기록 실패는 프로모션 덮어쓰기와 해제를 함께 롤백한다`() {
        put().expectStatus().isOk
        val existing = com.knk.manyak.admin.entity.AdminAuditLog(
            adminUserId = member.id, category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
            action = com.knk.manyak.admin.entity.AdminAuditAction.CHAT_MODEL_PROMOTION_UPSERTED,
            targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.CHAT_MODEL,
            targetId = "LITE", requestId = "matcher")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected audit failure"))
            .`when`(audits).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java) ?: existing)
        put(data = body(10)).expectStatus().is5xxServerError
        delete().expectStatus().is5xxServerError
        assertThat(models.findById("LITE").orElseThrow().promoTurnCost).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(1)
    }

    @Test fun `동시 덮어쓰기는 모델 잠금으로 감사 기록의 전후 순서를 유지한다`() {
        put().expectStatus().isOk
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val jobs = listOf(10L, 30L).map { cost -> pool.submit(java.util.concurrent.Callable {
                gate.await()
                put(data = body(cost)).expectStatus().isOk
            }) }
            gate.countDown()
            jobs.forEach { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val rows = jdbc.query("SELECT before_state, after_state FROM admin_audit_logs ORDER BY id") { rs, _ ->
            rs.getString("before_state") to rs.getString("after_state")
        }
        val mapper = tools.jackson.databind.ObjectMapper()
        assertThat(rows).hasSize(3)
        assertThat(mapper.readTree(rows[1].first!!)["turnCost"].asLong()).isZero()
        assertThat(mapper.readTree(rows[2].first!!)["turnCost"].asLong())
            .isEqualTo(mapper.readTree(rows[1].second!!)["turnCost"].asLong())
        assertThat(models.findById("LITE").orElseThrow().promoTurnCost)
            .isEqualTo(mapper.readTree(rows[2].second!!)["turnCost"].asLong())
        assertThat(mapper.readTree(rows[2].second!!).propertyNames()).containsExactlyInAnyOrder("turnCost", "startsAt", "endsAt")
    }

    @Test fun `정확한 시작은 포함하고 종료는 제외하며 해제 뒤 정가로 돌아간다`() {
        put().expectStatus().isOk
        val row = models.findById("LITE").orElseThrow()
        for ((at, expected) in listOf(row.promoStartsAt!!.minusNanos(1) to 20L, row.promoStartsAt!! to 0L,
            row.promoEndsAt!!.minusNanos(1) to 0L, row.promoEndsAt!! to 20L)) {
            val service = com.knk.manyak.chat.service.ChatModelService(models, java.time.Clock.fixed(at, java.time.ZoneOffset.UTC))
            assertThat(service.effective("LITE").turnCost).isEqualTo(expected)
            assertThat(service.list(member.id).first().promotion != null).isEqualTo(expected == 0L)
        }
        delete().expectStatus().isOk
        assertThat(com.knk.manyak.chat.service.ChatModelService(models).effective("LITE").turnCost).isEqualTo(20)
        client.get().uri(root).header("Authorization", "Bearer ${jwt.issueAdminToken(member.publicId)}")
            .exchange().expectStatus().isForbidden
    }

    @Test fun `회원만 프로모션을 받고 라이트 무료 기간에는 사용량 그대로 한도가 무제한이다`() {
        val trialsPath = "/api/v1/users/me/trials"
        trials.reserveMember(member.id, com.knk.manyak.credit.service.GuestTrialLimitService.Counter.CHAT_TURN)
        put().expectStatus().isOk
        client.get().uri("/api/v1/chats/models").header("Authorization", "Bearer $memberToken").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$[0].turnCost").isEqualTo(20)
            .jsonPath("$[0].promotion.turnCost").isEqualTo(0).jsonPath("$[0].promotion.endsAt").isEqualTo(end)
        client.get().uri("/api/v1/chats/models").exchange().expectStatus().isOk.expectBody().jsonPath("$[0].promotion").isEmpty
        client.get().uri(trialsPath).header("Authorization", "Bearer $memberToken").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.chatTurn.limit").isEmpty.jsonPath("$.chatTurn.used").isEqualTo(1)
        client.get().uri(trialsPath).header("X-Manyak-Device-Id", "promotion-guest").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.chatTurn.limit").isEqualTo(5)
        put(data = body(10)).expectStatus().isOk
        client.get().uri(trialsPath).header("Authorization", "Bearer $memberToken").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.chatTurn.limit").isEqualTo(5)
        delete().expectStatus().isOk
        put("AURORA").expectStatus().isOk
        client.get().uri(trialsPath).header("Authorization", "Bearer $memberToken").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.chatTurn.limit").isEqualTo(5)
        put(data = body(starts = end, ends = Instant.now().plusSeconds(7200).toString())).expectStatus().isOk
        client.get().uri("/api/v1/chats/models").header("Authorization", "Bearer $memberToken").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$[0].promotion").isEmpty
    }
}
