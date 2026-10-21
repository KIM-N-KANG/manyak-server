package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.story.submission.*
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@org.springframework.context.annotation.Import(com.knk.manyak.auth.controller.FakeSocialLoginConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAccountModerationIntegrationTests {
    @Autowired lateinit var client: RestTestClient
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    lateinit var users: UserRepository
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var cleaner: DatabaseCleaner
    @Autowired lateinit var submissions: StorySubmissionRepository
    private lateinit var admin: User
    private lateinit var token: String
    private val mapper = ObjectMapper()
    private val now = Instant.parse("2026-10-21T00:00:00Z")

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = jwt.issueAdminToken(admin.publicId)
    }
    private fun call(method: HttpMethod, path: String, body: Any = mapOf("reason" to "운영 확인"), auth: String? = token): RestTestClient.ResponseSpec {
        val request = client.method(method).uri("/api/v1/admin$path").contentType(MediaType.APPLICATION_JSON)
            .header("X-Manyak-Request-Id", "s7_request")
        auth?.let { request.header("Authorization", "Bearer $it") }
        return request.body(body).exchange()
    }
    private fun json(response: RestTestClient.ResponseSpec): JsonNode = mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun member(status: UserStatus = UserStatus.ACTIVE) = users.save(User(nickname = "회원", status = status))
    private fun held(user: User = admin, id: UUID = UUID.randomUUID(), at: Instant = now) = submissions.save(
        StorySubmission(userId = user.id, kind = SubmissionKind.CREATE, payload = "{\"title\":\"synthetic-private-text\"}",
            inputForm = "{\"thumbnailUrl\":\"https://example.test/private-image\"}", publicId = id,
            heldAt = at, holdReason = "MODEL_CALL_FAILED", retryCount = 2, attempt = 3, createdAt = now.minusSeconds(60)))

    @Test fun `계정 정지와 해제는 상태와 사유를 감사에 남긴다`() {
        val user = member()
        val path = "/members/${user.publicId}/suspension"
        assertThat(json(call(HttpMethod.POST, path).expectStatus().isOk)["status"].asText()).isEqualTo("SUSPENDED")
        assertThat(users.findById(user.id).orElseThrow().status).isEqualTo(UserStatus.SUSPENDED)
        assertThat(json(call(HttpMethod.DELETE, path).expectStatus().isOk)["status"].asText()).isEqualTo("ACTIVE")
        val rows = jdbc.queryForList("SELECT * FROM admin_audit_logs ORDER BY id")
        assertThat(rows.map { it["action"] }).containsExactly("MEMBER_SUSPENDED", "MEMBER_UNSUSPENDED")
        assertThat(rows.first()["target_id"]).isEqualTo(user.publicId.toString())
        assertThat(rows.first()["reason"]).isEqualTo("운영 확인")
        assertThat(rows.map { it["admin_user_id"] }).containsOnly(admin.id)
        assertThat(rows.map { it["category"] }).containsOnly("CHANGE")
        assertThat(rows.map { it["target_type"] }).containsOnly("MEMBER")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs ORDER BY id LIMIT 1", String::class.java)!!)["status"].asText()).isEqualTo("ACTIVE")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT after_state FROM admin_audit_logs ORDER BY id LIMIT 1", String::class.java)!!)["status"].asText()).isEqualTo("SUSPENDED")
        assertThat(rows.map { it["request_id"] }).containsOnly("s7_request")
    }

    @Test fun `보류 목록은 안전한 요약을 페이지로 반환한다`() {
        val row = held()
        val body = json(call(HttpMethod.GET, "/moderation/holds").expectStatus().isOk)
        assertThat(body["items"][0]["submissionId"].asText()).isEqualTo(row.publicId.toString())
        assertThat(body["items"][0]["ownerId"].asText()).isEqualTo(admin.publicId.toString())
        assertThat(body["items"][0].properties().map { it.key }).containsExactlyInAnyOrder("submissionId", "kind", "storyId", "ownerId", "holdReason", "attempt", "heldAt", "createdAt")
        assertThat(body["size"].asInt()).isEqualTo(20)
        assertThat(body.toString()).doesNotContain("synthetic-private-text", "private-image", "payload", "inputForm")
    }

    @Test fun `보류 해제는 재시도와 임대를 초기화하고 감사에 원인과 회차를 보존한다`() {
        val row = held()
        val body = json(call(HttpMethod.POST, "/moderation/holds/${row.publicId}/release").expectStatus().isOk)
        assertThat(body["status"].asText()).isEqualTo("PENDING")
        assertThat(body["attempt"].asInt()).isEqualTo(3)
        val saved = submissions.findById(row.id).orElseThrow()
        assertThat(saved.payload).isEqualTo(row.payload)
        assertThat(saved.inputForm).isEqualTo(row.inputForm)
        assertThat(saved.attempt).isEqualTo(row.attempt)
        assertThat(saved.heldAt).isNull()
        assertThat(saved.holdReason).isNull()
        assertThat(saved.nextAttemptAt).isNull()
        assertThat(saved.dispatchedAt).isNull()
        assertThat(saved.retryCount).isZero()
        val audit = jdbc.queryForMap("SELECT * FROM admin_audit_logs")
        assertThat(audit["action"]).isEqualTo("SUBMISSION_HOLD_RELEASED")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs", String::class.java)!!)["holdReason"].asText()).isEqualTo("MODEL_CALL_FAILED")
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT before_state FROM admin_audit_logs", String::class.java)!!)["attempt"].asInt()).isEqualTo(3)
        assertThat(audit["after_state"]).isNull()
    }
    @Autowired lateinit var claims: SubmissionClaimStore
    @Autowired lateinit var submissionService: StorySubmissionService
    @Autowired lateinit var authTokens: com.knk.manyak.auth.token.AuthTokenService
    @Autowired lateinit var withdrawal: com.knk.manyak.user.service.UserWithdrawalService
    @Autowired lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    lateinit var auditRepository: com.knk.manyak.admin.repository.AdminAuditLogRepository

    @Test fun `새 관리자 네 경로는 앱 토큰과 무토큰 및 역할 없는 관리자 토큰을 거절한다`() {
        val user = member()
        val row = held(user)
        val targets = listOf(HttpMethod.POST to "/members/${user.publicId}/suspension", HttpMethod.DELETE to "/members/${user.publicId}/suspension",
            HttpMethod.GET to "/moderation/holds", HttpMethod.POST to "/moderation/holds/${row.publicId}/release")
        for ((method, path) in targets) {
            for (auth in listOf(null, jwt.issueAccessToken(user.publicId))) call(method, path, auth = auth).expectStatus().isUnauthorized
            call(method, path, auth = jwt.issueAdminToken(user.publicId)).expectStatus().isForbidden
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `계정 상태 거절과 관리자 보호 및 역할 회수를 적용한다`() {
        val suspended = member(UserStatus.SUSPENDED)
        val active = member()
        val deleted = member(UserStatus.DELETED)
        for ((method, user) in listOf(HttpMethod.POST to suspended, HttpMethod.DELETE to active,
            HttpMethod.POST to deleted, HttpMethod.DELETE to deleted)) {
            call(method, "/members/${user.publicId}/suspension").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("CONFLICT")
        }
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", active.id)
        for (user in listOf(admin, active)) {
            call(HttpMethod.POST, "/members/${user.publicId}/suspension").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ADMIN_ACCOUNT_PROTECTED")
            assertThat(users.findById(user.id).orElseThrow().status).isEqualTo(UserStatus.ACTIVE)
        }
        // 해제에는 관리자 보호를 적용하지 않는다.
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", suspended.id)
        call(HttpMethod.DELETE, "/members/${suspended.publicId}/suspension").expectStatus().isOk
        jdbc.update("DELETE FROM admin_accounts WHERE user_id = ?", active.id)
        call(HttpMethod.POST, "/members/${active.publicId}/suspension").expectStatus().isOk
    }

    @Test fun `변경 요청은 사유와 공개 UUID를 검증하고 없는 대상은 404다`() {
        val user = member()
        val row = held()
        for ((method, path) in listOf(HttpMethod.POST to "/members/${user.publicId}/suspension",
            HttpMethod.DELETE to "/members/${user.publicId}/suspension", HttpMethod.POST to "/moderation/holds/${row.publicId}/release")) {
            for (body in listOf<Any>(emptyMap<String, String>(), mapOf("reason" to ""), mapOf("reason" to "   "), mapOf("reason" to "a".repeat(501)), "{broken")) {
                call(method, path, body).expectStatus().isBadRequest
            }
            call(method, path.replace(user.publicId.toString(), "not-uuid").replace(row.publicId.toString(), "not-uuid")).expectStatus().isBadRequest
            call(method, path.replace(user.publicId.toString(), UUID.randomUUID().toString()).replace(row.publicId.toString(), UUID.randomUUID().toString())).expectStatus().isNotFound
        }
        call(HttpMethod.POST, "/members/${user.publicId}/suspension", mapOf("reason" to "a".repeat(500))).expectStatus().isOk
        assertThat(jdbc.queryForObject("SELECT reason FROM admin_audit_logs", String::class.java)).hasSize(500)
    }

    @Test fun `정지 뒤 조회는 가능하고 쓰기와 회전은 거절하며 해제 즉시 쓰기를 허용한다`() {
        val user = member()
        val session = authTokens.issueTokens(user)
        val path = "/members/${user.publicId}/suspension"
        call(HttpMethod.POST, path).expectStatus().isOk
        client.get().uri("/api/v1/auth/me").header("Authorization", "Bearer ${session.accessToken}").exchange()
            .expectStatus().isOk.expectBody().jsonPath("$.status").isEqualTo("SUSPENDED")
        fun profile() = client.patch().uri("/api/v1/users/me").header("Authorization", "Bearer ${session.accessToken}")
            .contentType(MediaType.APPLICATION_JSON).body(mapOf("nickname" to "변경회원")).exchange()
        profile().expectStatus().isForbidden
        fun refresh() = client.post().uri("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("refreshToken" to session.refreshToken)).exchange()
        refresh().expectStatus().isForbidden // 정지 자체가 세션을 폐기했다면 여기서 401이 된다.
        refresh().expectStatus().isUnauthorized
        call(HttpMethod.DELETE, path).expectStatus().isOk
        profile().expectStatus().isOk
    }

    @Test fun `관리자 정지 뒤 탈퇴와 재가입은 정지 상태를 승계한다`() {
        fun login() = json(client.post().uri("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("idToken" to "s7-rejoin")).exchange().expectStatus().isOk)
        val session = login()
        val user = users.findAll().single { it.id != admin.id }
        call(HttpMethod.POST, "/members/${user.publicId}/suspension").expectStatus().isOk
        client.delete().uri("/api/v1/users/me").header("Authorization", "Bearer ${session["accessToken"].asText()}")
            .exchange().expectStatus().isNoContent
        assertThat(users.findById(user.id).orElseThrow().withdrawnFromStatus).isEqualTo(UserStatus.SUSPENDED)
        val rejoined = login()
        client.get().uri("/api/v1/auth/me").header("Authorization", "Bearer ${rejoined["accessToken"].asText()}")
            .exchange().expectStatus().isOk.expectBody().jsonPath("$.status").isEqualTo("SUSPENDED")
    }

    @Test fun `보류 목록은 보류만 공개 UUID 동률과 양방향 정렬로 페이지 처리한다`() {
        val low = held(id = UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val high = held(id = UUID.fromString("00000000-0000-4000-8000-000000000002"))
        for (state in SubmissionStatus.entries) submissions.save(StorySubmission(userId = admin.id, kind = SubmissionKind.CREATE, payload = "{}", status = state))
        submissions.save(StorySubmission(userId = admin.id, kind = SubmissionKind.CREATE, payload = "{}", nextAttemptAt = now.plusSeconds(600)))
        for (sort in listOf("heldAt", "createdAt")) for (order in listOf("asc", "desc")) {
            val one = json(call(HttpMethod.GET, "/moderation/holds?size=1&sort=$sort&order=$order").expectStatus().isOk)
            assertThat(one["items"][0]["submissionId"].asText()).isEqualTo(low.publicId.toString())
            assertThat(one["totalElements"].asLong()).isEqualTo(2)
            assertThat(one["totalPages"].asLong()).isEqualTo(2)
            val two = json(call(HttpMethod.GET, "/moderation/holds?size=1&page=2&sort=$sort&order=$order").expectStatus().isOk)
            assertThat(two["items"][0]["submissionId"].asText()).isEqualTo(high.publicId.toString())
        }
        assertThat(json(call(HttpMethod.GET, "/moderation/holds?page=2147483647&size=100").expectStatus().isOk)["items"].size()).isZero()
        val earlier = held(at = now.minusSeconds(60))
        assertThat(json(call(HttpMethod.GET, "/moderation/holds").expectStatus().isOk)["items"][0]["submissionId"].asText()).isEqualTo(earlier.publicId.toString())
        assertThat(json(call(HttpMethod.GET, "/moderation/holds?order=desc").expectStatus().isOk)["items"][2]["submissionId"].asText()).isEqualTo(earlier.publicId.toString())
    }

    @Test fun `보류 목록의 수정본은 스토리 공개 UUID를 반환하며 잘못된 페이지는 400이다`() {
        val story = stories.save(com.knk.manyak.story.entity.Story(userId = admin.id, title = "원문"))
        submissions.save(StorySubmission(userId = admin.id, storyId = story.id, kind = SubmissionKind.UPDATE, payload = "{}",
            heldAt = now, holdReason = "MODERATION_UNAVAILABLE"))
        val first = json(call(HttpMethod.GET, "/moderation/holds").expectStatus().isOk)["items"][0]
        assertThat(first["storyId"].asText()).isEqualTo(story.publicId.toString())
        assertThat(first["kind"].asText()).isEqualTo("UPDATE")
        for (query in listOf("page=0", "page=-1", "page=bad", "page=2147483648", "size=0", "size=101", "size=x", "order=ASC", "sort=payload", "limit=20", "cursor=x")) {
            call(HttpMethod.GET, "/moderation/holds?$query").expectStatus().isBadRequest
        }
        submissions.deleteAll()
        val empty = json(call(HttpMethod.GET, "/moderation/holds").expectStatus().isOk)
        assertThat(empty["totalPages"].asLong()).isZero()
        assertThat(empty["items"].size()).isZero()
    }
    @Autowired lateinit var stories: com.knk.manyak.story.repository.StoryRepository

    @Test fun `보류가 아니면 해제를 거절하고 소유자 취소 뒤에는 404다`() {
        for (state in SubmissionStatus.entries) {
            val row = submissions.save(StorySubmission(userId = admin.id, kind = SubmissionKind.CREATE, payload = "{}", status = state))
            call(HttpMethod.POST, "/moderation/holds/${row.publicId}/release").expectStatus().isEqualTo(409)
        }
        val scheduled = submissions.save(StorySubmission(userId = admin.id, kind = SubmissionKind.CREATE, payload = "{}", nextAttemptAt = now.plusSeconds(600)))
        call(HttpMethod.POST, "/moderation/holds/${scheduled.publicId}/release").expectStatus().isEqualTo(409)
        val cancelled = held()
        submissionService.delete(cancelled.publicId.toString(), admin.id)
        call(HttpMethod.POST, "/moderation/holds/${cancelled.publicId}/release").expectStatus().isNotFound
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `해제 뒤 다음 선점은 새 회차를 받고 재시도 예산을 다시 사용한다`() {
        val row = held()
        jdbc.update("UPDATE story_submissions SET dispatched_at = ? WHERE id = ?", java.sql.Timestamp.from(now), row.id)
        call(HttpMethod.POST, "/moderation/holds/${row.publicId}/release").expectStatus().isOk
        val work = claims.claim(Instant.now(), java.time.Duration.ofSeconds(300), 1).single()
        assertThat(work.attempt).isEqualTo(4)
        assertThat(submissions.findById(row.id).orElseThrow().retryCount).isZero()
        transactions.fail(row.id, work.attempt, "MODERATION_UNAVAILABLE")
        val saved = submissions.findById(row.id).orElseThrow()
        assertThat(saved.retryCount).isEqualTo(1)
        assertThat(saved.nextAttemptAt).isNotNull()
        assertThat(saved.heldAt).isNull()
    }
    @Autowired lateinit var transactions: SubmissionTransactions

    @Test fun `감사 삽입 장애는 정지와 해제 및 보류 해제를 모두 롤백한다`() {
        val active = member()
        val suspended = member(UserStatus.SUSPENDED)
        val row = held()
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java)
                ?: com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id,
                    category = com.knk.manyak.admin.entity.AdminAuditCategory.CHANGE,
                    action = com.knk.manyak.admin.entity.AdminAuditAction.MEMBER_SUSPENDED,
                    targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.MEMBER, targetId = "fixture", requestId = "fixture"))
        call(HttpMethod.POST, "/members/${active.publicId}/suspension").expectStatus().is5xxServerError
        call(HttpMethod.DELETE, "/members/${suspended.publicId}/suspension").expectStatus().is5xxServerError
        call(HttpMethod.POST, "/moderation/holds/${row.publicId}/release").expectStatus().is5xxServerError
        assertThat(users.findById(active.id).orElseThrow().status).isEqualTo(UserStatus.ACTIVE)
        assertThat(users.findById(suspended.id).orElseThrow().status).isEqualTo(UserStatus.SUSPENDED)
        assertThat(submissions.findById(row.id).orElseThrow().heldAt).isEqualTo(now)
        assertThat(submissions.findById(row.id).orElseThrow().retryCount).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    private fun concurrentStatuses(path: String): List<Int> {
        val ready = java.util.concurrent.CountDownLatch(2)
        val go = java.util.concurrent.CountDownLatch(1)
        val secondAdmin = member()
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", secondAdmin.id)
        val tokens = listOf(token, jwt.issueAdminToken(secondAdmin.publicId))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = tokens.map { auth ->
                executor.submit<Int> {
                    ready.countDown()
                    check(go.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    call(HttpMethod.POST, path, auth = auth).expectBody(String::class.java).returnResult().status.value()
                }
            }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            go.countDown()
            return futures.map { it.get(15, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    @Test fun `같은 회원의 동시 정지는 한 번만 성공하고 감사 1행을 남긴다`() {
        val user = member()
        assertThat(concurrentStatuses("/members/${user.publicId}/suspension")).containsExactlyInAnyOrder(200, 409)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(1)
    }

    @Test fun `같은 제출본의 동시 해제는 한 번만 성공하고 감사 1행을 남긴다`() {
        val row = held()
        assertThat(concurrentStatuses("/moderation/holds/${row.publicId}/release")).containsExactlyInAnyOrder(200, 409)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(1)
    }

    @Test fun `탈퇴 잠금이 먼저 커밋되면 대기한 정지는 탈퇴 상태를 다시 읽고 거절한다`() {
        val user = member()
        val lockRequested = java.util.concurrent.CountDownLatch(1)
        org.mockito.Mockito.doAnswer { invocation ->
            lockRequested.countDown()
            org.mockito.Mockito.mockingDetails(users).mockCreationSettings.defaultAnswer.answer(invocation)
        }.`when`(users).findByPublicIdForUpdate(user.publicId)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var future: java.util.concurrent.Future<Int>? = null
        try {
            org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                withdrawal.withdraw(user.id)
                future = executor.submit<Int> { call(HttpMethod.POST, "/members/${user.publicId}/suspension").expectBody(String::class.java).returnResult().status.value() }
                check(lockRequested.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            assertThat(future!!.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(409)
            val saved = users.findById(user.id).orElseThrow()
            assertThat(saved.status).isEqualTo(UserStatus.DELETED)
            assertThat(saved.withdrawnFromStatus).isEqualTo(UserStatus.ACTIVE)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
        } finally { executor.shutdownNow() }
    }
}
