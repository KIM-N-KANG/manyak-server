package com.knk.manyak.admin

import com.knk.manyak.auth.controller.FakeSocialLoginConfig
import com.knk.manyak.auth.entity.*
import com.knk.manyak.auth.repository.*
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper

@Import(FakeSocialLoginConfig::class, AdminProbeConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminFoundationIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var socials: SocialAccountRepository
    @Autowired private lateinit var jwt: JwtTokenProvider
    @Autowired private lateinit var cleaner: DatabaseCleaner
    @Autowired private lateinit var jdbc: JdbcTemplate
    private val mapper = ObjectMapper()
    private val loginPath = "/api/v1/admin/auth/login/google"

    @BeforeEach fun clean() = cleaner.cleanAll()

    private fun member(sub: String, status: UserStatus = UserStatus.ACTIVE, admin: Boolean = true): User {
        val user = users.save(User(nickname = sub, status = status))
        socials.save(SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = sub))
        if (admin) jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", user.id)
        return user
    }

    private fun login(sub: String) = client.post().uri(loginPath).header("Authorization", "Bearer ignored")
        .contentType(MediaType.APPLICATION_JSON).body(mapOf("idToken" to sub)).exchange()

    @Autowired private lateinit var mappings: List<org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping>

    private fun adminToken(user: User): String {
        val sub = socials.findByUserId(user.id).single().providerUserId
        return mapper.readTree(login(sub).expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)["accessToken"].asText()
    }

    @Autowired private lateinit var audits: com.knk.manyak.admin.service.AdminAuditService
    @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var auditRepository: com.knk.manyak.admin.repository.AdminAuditLogRepository
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var events: com.knk.manyak.global.observability.StructuredLogger
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var analytics: com.knk.manyak.global.observability.analytics.ServerAnalytics

    @Test fun `변경 기록은 처리자 대상 전후 값과 응답과 같은 요청 ID를 보존한다`() {
        val user = member("admin")
        val token = adminToken(user)
        for (requestId in listOf("x".repeat(64), "x".repeat(65), "has space")) {
            val result = client.put().uri("/api/v1/admin/foundation-probe").header("Authorization", "Bearer $token")
                .header("X-Manyak-Request-Id", requestId).exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult()
            val actualId = result.responseHeaders.getFirst("X-Manyak-Request-Id")!!
            assertThat(mapper.readTree(result.responseBody!!)["requestId"].asText()).isEqualTo(actualId)
            if (requestId.length == 64) assertThat(actualId).isEqualTo(requestId)
            else assertThat(actualId).matches("req_[a-f0-9]{32}")
            val row = jdbc.queryForMap("SELECT * FROM admin_audit_logs ORDER BY id DESC LIMIT 1")
            assertThat(row["admin_user_id"]).isEqualTo(user.id)
            assertThat(row["category"]).isEqualTo("CHANGE")
            assertThat(row["action"]).isEqualTo("MEMBER_SUSPENDED")
            assertThat(row["target_type"]).isEqualTo("MEMBER")
            assertThat(row["target_id"]).isEqualTo("00000000-0000-4000-8000-000000000001")
            assertThat(row["reason"]).isEqualTo("테스트 사유")
            assertThat(row["request_id"]).isEqualTo(actualId)
            val before = jdbc.queryForObject("SELECT before_state FROM admin_audit_logs ORDER BY id DESC LIMIT 1", String::class.java)!!
            assertThat(mapper.readTree(before)["status"].asText()).isEqualTo("ACTIVE")
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(3)
    }

    @Test fun `감사 저장 실패면 변경과 로그 모두 롤백되고 접속 응답도 실패한다`() {
        val user = member("admin")
        val token = adminToken(user)
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java)
                ?: com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = user.id,
                    category = com.knk.manyak.admin.entity.AdminAuditCategory.ACCESS,
                    action = com.knk.manyak.admin.entity.AdminAuditAction.MEMBER_VIEWED,
                    targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.MEMBER,
                    targetId = user.publicId.toString(), requestId = "matcher"))
        client.put().uri("/api/v1/admin/foundation-probe").header("Authorization", "Bearer $token")
            .exchange().expectStatus().is5xxServerError
        assertThat(users.findById(user.id).orElseThrow().nickname).isEqualTo("admin")
        client.post().uri("/api/v1/admin/foundation-probe/access").header("Authorization", "Bearer $token")
            .exchange().expectStatus().is5xxServerError.expectBody().jsonPath("$.result").doesNotExist()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `접속 기록은 각각 독립 커밋되고 사유와 전후 값은 없다`() {
        val user = member("admin")
        val token = adminToken(user)
        repeat(2) {
            client.post().uri("/api/v1/admin/foundation-probe/access").header("Authorization", "Bearer $token")
                .exchange().expectStatus().isOk
        }
        val rows = jdbc.queryForList("SELECT * FROM admin_audit_logs ORDER BY id")
        assertThat(rows).hasSize(4)
        assertThat(rows.map { it["action"] }).containsExactly("MEMBER_SEARCHED", "MEMBER_VIEWED", "MEMBER_SEARCHED", "MEMBER_VIEWED")
        assertThat(rows.first()["target_id"]).isEqualTo("검색어")
        rows.forEach {
            assertThat(it["category"]).isEqualTo("ACCESS")
            assertThat(it["reason"]).isNull()
            assertThat(it["before_state"]).isNull()
            assertThat(it["after_state"]).isNull()
        }
        org.slf4j.MDC.put("request_id", "outer_rollback")
        try {
            val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
            org.assertj.core.api.Assertions.assertThatThrownBy {
                tx.executeWithoutResult {
                    audits.recordAccess(user.id, com.knk.manyak.admin.entity.AdminAuditAction.MEMBER_VIEWED, user.publicId.toString())
                    error("outer rollback")
                }
            }.isInstanceOf(IllegalStateException::class.java)
        } finally { org.slf4j.MDC.clear() }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(5)
    }

    @Test fun `변경 감사는 트랜잭션이 없으면 거부하고 사유와 JSON 한도를 검사한다`() {
        val user = member("admin")
        val action = com.knk.manyak.admin.entity.AdminAuditAction.MEMBER_SUSPENDED
        org.assertj.core.api.Assertions.assertThatThrownBy { audits.recordChange(user.id, action, user.publicId.toString(), "reason") }
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException::class.java)
        val tx = org.springframework.transaction.support.TransactionTemplate(transactionManager)
        org.slf4j.MDC.put("request_id", "validation")
        try {
            for (reason in listOf("", " ", "x".repeat(501))) {
                org.assertj.core.api.Assertions.assertThatThrownBy {
                    tx.executeWithoutResult { audits.recordChange(user.id, action, user.publicId.toString(), reason) }
                }.isInstanceOf(IllegalArgumentException::class.java)
            }
            org.assertj.core.api.Assertions.assertThatThrownBy {
                tx.executeWithoutResult { audits.recordChange(user.id, action, user.publicId.toString(), "reason", after = mapper.readTree("{\"value\":\"${"x".repeat(4000)}\"}")) }
            }.isInstanceOf(IllegalArgumentException::class.java)
        } finally { org.slf4j.MDC.clear() }
    }

    @Test fun `등록된 관리자 핸들러 전체는 무토큰과 일반 회원 토큰을 거절한다`() {
        val ordinary = member("ordinary", admin = false)
        val targets = mappings.flatMap { it.handlerMethods.keys }.flatMap { mapping ->
            mapping.patternValues.filter { it.startsWith("/api/v1/admin/") && it != loginPath }.flatMap { path ->
                val methods = mapping.methodsCondition.methods.map { org.springframework.http.HttpMethod.valueOf(it.name) }
                    .ifEmpty { listOf(org.springframework.http.HttpMethod.GET) }
                methods.map { it to path.replace(Regex("\\{[^}]+}"), "00000000-0000-4000-8000-000000000001") }
            }
        }
        assertThat(targets).isNotEmpty()
        println("관리자 회귀 대상 핸들러 수: ${targets.size}")
        for ((method, path) in targets) {
            for (token in listOf(null, jwt.issueAccessToken(ordinary.publicId))) {
                val spec = client.method(method).uri(path).contentType(MediaType.APPLICATION_JSON)
                token?.let { spec.header("Authorization", "Bearer $it") }
                spec.body("{}").exchange().expectStatus().isUnauthorized.expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
            }
        }
    }

    @Test fun `유효한 관리자 토큰도 역할과 계정 상태를 매 요청 다시 확인한다`() {
        val user = member("admin")
        val token = adminToken(user)
        fun call() = client.get().uri("/api/v1/admin/foundation-probe").header("Authorization", "Bearer $token").exchange()
        call().expectStatus().isOk.expectBody().jsonPath("$.adminUserId").isEqualTo(user.id.toInt())
        jdbc.update("DELETE FROM admin_accounts WHERE user_id = ?", user.id)
        call().expectStatus().isForbidden
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", user.id)
        for (status in listOf("SUSPENDED", "DELETED")) {
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, user.id)
            call().expectStatus().isForbidden.expectBody().jsonPath("$.message").isEqualTo("관리자 권한이 없습니다.")
        }
        jdbc.update("DELETE FROM social_accounts WHERE user_id = ?", user.id)
        jdbc.update("DELETE FROM admin_accounts WHERE user_id = ?", user.id)
        jdbc.update("DELETE FROM users WHERE id = ?", user.id)
        call().expectStatus().isForbidden
    }

    @Test fun `관리자 토큰은 앱 필수 경로에서 거절하고 선택 인증에서는 익명으로 처리한다`() {
        val user = member("admin")
        val token = adminToken(user)
        client.get().uri("/api/v1/auth/me").header("Authorization", "Bearer $token").exchange().expectStatus().isUnauthorized
        for (path in listOf("/api/v1/credits/policies", "/api/v1/stories")) {
            client.get().uri(path).header("Authorization", "Bearer $token").exchange().expectStatus().isOk
        }
        client.post().uri("/api/v1/auth/login/google").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("idToken" to "invalid")).exchange().expectStatus().isUnauthorized
        client.post().uri("/api/v1/auth/login/google").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("idToken" to "admin")).exchange().expectStatus().isOk
    }

    @Test fun `관리자 로그인은 전용 토큰과 회원 요약만 발급한다`() {
        val user = member("admin")
        val body = mapper.readTree(login("admin").expectStatus().isOk.expectBody(String::class.java).returnResult().responseBody!!)
        assertThat(body["expiresIn"].asLong()).isEqualTo(7200)
        assertThat(body["tokenType"].asText()).isEqualTo("Bearer")
        assertThat(body["admin"]["memberId"].asText()).isEqualTo(user.publicId.toString())
        assertThat(body["admin"]["isAdmin"].asBoolean()).isTrue()
        assertThat(body.has("refreshToken")).isFalse()
        assertThat(org.mockito.Mockito.mockingDetails(events).invocations.any {
            it.method.name == "event" && it.arguments[0] == "admin_login_succeeded" &&
                (it.arguments[1] as? Map<*, *>)?.get("admin_public_id") == user.publicId.toString()
        }).isTrue()
        assertThat(org.mockito.Mockito.mockingDetails(analytics).invocations).isEmpty()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Long::class.java)).isZero()
        assertThat(socials.findByUserId(user.id).single().lastLoginAt).isNull()
        val claims = com.nimbusds.jwt.SignedJWT.parse(body["accessToken"].asText()).jwtClaimsSet
        assertThat(claims.audience).containsExactly("manyak-admin")
        assertThat(claims.subject).isEqualTo(user.publicId.toString())
        assertThat(claims.expirationTime.time - claims.issueTime.time).isEqualTo(7200000)
    }

    @Test fun `자격 없음은 모두 같은 403이며 신원 오류는 401이다`() {
        member("ordinary", admin = false)
        member("suspended", UserStatus.SUSPENDED)
        member("deleted", UserStatus.DELETED)
        for (sub in listOf("ordinary", "suspended", "deleted", "missing")) {
            login(sub).expectStatus().isForbidden.expectBody().jsonPath("$.message").isEqualTo("관리자 권한이 없습니다.")
        }
        val reasons = org.mockito.Mockito.mockingDetails(events).invocations.filter {
            it.method.name == "event" && it.arguments[0] == "admin_login_rejected" && it.arguments[1] is Map<*, *>
        }.map { (it.arguments[1] as Map<*, *>)["reason"] }
        assertThat(reasons).containsExactlyInAnyOrder("no_role", "suspended", "deleted", "no_member")
        assertThat(org.mockito.Mockito.mockingDetails(analytics).invocations).isEmpty()
        login("invalid").expectStatus().isUnauthorized
        assertThat(users.count()).isEqualTo(3)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Long::class.java)).isZero()
    }

    @Test fun `로그인 필수 입력 누락과 공백은 400이다`() {
        client.post().uri(loginPath).contentType(MediaType.APPLICATION_JSON).body("{}").exchange().expectStatus().isBadRequest
        login(" ").expectStatus().isBadRequest
    }
}
