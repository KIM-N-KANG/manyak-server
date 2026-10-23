package com.knk.manyak.admin

import com.knk.manyak.auth.entity.User
import com.knk.manyak.auth.entity.UserStatus
import com.knk.manyak.auth.entity.SocialAccount
import com.knk.manyak.auth.entity.SocialProvider
import com.knk.manyak.auth.repository.UserRepository
import com.knk.manyak.auth.repository.SocialAccountRepository
import com.knk.manyak.auth.jwt.JwtTokenProvider
import com.knk.manyak.support.DatabaseCleaner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

@org.springframework.context.annotation.Import(AdminReadClockConfig::class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["manyak.admin.dashboard.excluded-user-ids="])
class AdminReadApiIntegrationTests {
    @Autowired private lateinit var client: RestTestClient
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var socials: SocialAccountRepository
    @Autowired private lateinit var tokens: JwtTokenProvider
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var cleaner: DatabaseCleaner
    private lateinit var admin: User
    private lateinit var token: String
    private val mapper = ObjectMapper()

    @BeforeEach fun setup() {
        cleaner.cleanAll()
        admin = users.save(User(nickname = "관리자"))
        jdbc.update("INSERT INTO admin_accounts(user_id, granted_at) VALUES (?, CURRENT_TIMESTAMP)", admin.id)
        token = tokens.issueAdminToken(admin.publicId)
    }
    private fun get(path: String) = client.get().uri("/api/v1/admin$path").header("Authorization", "Bearer $token").exchange()
    private fun json(response: RestTestClient.ResponseSpec): JsonNode =
        mapper.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
    private fun member(name: String, status: UserStatus = UserStatus.ACTIVE, id: UUID = UUID.randomUUID(),
                       created: Instant = Instant.parse("2026-01-01T00:00:00Z")) =
        users.save(User(nickname = name, status = status, publicId = id, createdAt = created))

    @Autowired private lateinit var audits: com.knk.manyak.admin.service.AdminAuditService
    @Autowired private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @Autowired private lateinit var orders: com.knk.manyak.credit.repository.CreditOrderRepository
    @Autowired private lateinit var lots: com.knk.manyak.credit.repository.CreditLotRepository
    @Autowired private lateinit var storyRepository: com.knk.manyak.story.repository.StoryRepository
    @Autowired private lateinit var chatRepository: com.knk.manyak.chat.repository.StoryChatRepository
    @Autowired private lateinit var versions: com.knk.manyak.chat.repository.StoryMessageVersionRepository
    @Autowired private lateinit var messageRepository: com.knk.manyak.chat.repository.StoryMessageRepository
    @Autowired private lateinit var dashboardRepository: com.knk.manyak.admin.repository.AdminDashboardRepository
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var auditRepository: com.knk.manyak.admin.repository.AdminAuditLogRepository
    private val now = Instant.parse("2026-10-20T15:30:00Z")
    private val midnight = Instant.parse("2026-10-20T15:00:00Z")

    private fun order(user: User = admin, status: com.knk.manyak.credit.entity.CreditOrderStatus = com.knk.manyak.credit.entity.CreditOrderStatus.COMPLETED,
                      completed: Instant? = now, price: Long = 4800, credit: Long = 5000, id: UUID = UUID.randomUUID(), refund: Instant? = null) =
        orders.save(com.knk.manyak.credit.entity.CreditOrder(userId = user.id, productId = "if_5000",
            provider = com.knk.manyak.credit.entity.CreditOrderProvider.GROBLE, status = status, priceKrw = price, creditAmount = credit,
            completedAt = completed, publicId = id, refundedAt = refund))

    @Test fun `회원 가입 시각 정렬은 양방향을 지원하고 탈퇴 연동 이메일은 검색하지 않는다`() {
        val early = member("가입순", created = midnight.minusSeconds(60))
        val late = member("가입순", created = midnight)
        assertThat(json(get("/members?q=가입순&order=asc").expectStatus().isOk)["items"][0]["memberId"].asText()).isEqualTo(early.publicId.toString())
        assertThat(json(get("/members?q=가입순&order=desc").expectStatus().isOk)["items"][0]["memberId"].asText()).isEqualTo(late.publicId.toString())
        socials.save(SocialAccount(userId = early.id, provider = SocialProvider.GOOGLE, providerUserId = "removed-link", email = "removed@example.test", deletedAt = now))
        assertThat(json(get("/members?q=removed@example.test").expectStatus().isOk)["totalElements"].asLong()).isZero()
    }

    @Test fun `감사 페이지의 동률은 공개 UUID로 고정하고 과거 처리자의 현재 상태를 보여 준다`() {
        val actor = member("탈퇴 처리자", UserStatus.DELETED)
        val low = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val high = UUID.fromString("00000000-0000-4000-8000-000000000002")
        for (id in listOf(high, low)) {
            jdbc.update("""INSERT INTO admin_audit_logs(public_id, admin_user_id, category, action, target_type, target_id, request_id, created_at)
                VALUES (?, ?, 'ACCESS', 'MEMBER_VIEWED', 'MEMBER', ?, 'fixture', ?)""", id, actor.id, actor.publicId.toString(), java.sql.Timestamp.from(now))
        }
        val first = json(get("/audit-logs?size=1").expectStatus().isOk)
        assertThat(first["items"][0]["auditLogId"].asText()).isEqualTo(low.toString())
        assertThat(first["items"][0]["admin"]["status"].asText()).isEqualTo("DELETED")
        assertThat(first["items"][0]["admin"]["isAdmin"].asBoolean()).isFalse()
        assertThat(first["totalPages"].asLong()).isEqualTo(2)
        assertThat(json(get("/audit-logs?size=1&page=2").expectStatus().isOk)["items"][0]["auditLogId"].asText()).isEqualTo(high.toString())
        val older = UUID.randomUUID()
        jdbc.update("""INSERT INTO admin_audit_logs(public_id, admin_user_id, category, action, target_type, target_id, request_id, created_at)
            VALUES (?, ?, 'ACCESS', 'MEMBER_VIEWED', 'MEMBER', ?, 'fixture', ?)""", older, actor.id, actor.publicId.toString(), java.sql.Timestamp.from(midnight))
        assertThat(json(get("/audit-logs?order=asc").expectStatus().isOk)["items"][0]["auditLogId"].asText()).isEqualTo(older.toString())
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(3)
    }

    @Test fun `검색어 없는 회원 목록은 접속 기록을 남기지 않는다`() {
        repeat(22) { member("목록$it") }
        val first = json(get("/members").expectStatus().isOk)
        assertThat(first["items"].size()).isEqualTo(20)
        assertThat(first["totalElements"].asLong()).isEqualTo(23)
        assertThat(first["totalPages"].asLong()).isEqualTo(2)
        assertThat(json(get("/members?page=2").expectStatus().isOk)["items"].size()).isEqualTo(3)
        assertThat(jdbc.queryForList("SELECT target_id FROM admin_audit_logs", String::class.java)).isEmpty()
    }

    @Test fun `결제는 완료와 환불만 페이지로 조회하고 상태와 KST 양끝 날짜를 필터한다`() {
        order(completed = midnight.minusSeconds(1))
        val included = order(completed = midnight)
        order(completed = midnight.plusSeconds(86400).minusSeconds(1), status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, refund = now)
        order(completed = midnight.plusSeconds(86400))
        order(completed = null, status = com.knk.manyak.credit.entity.CreditOrderStatus.PENDING)
        val body = json(get("/payments/orders?from=2026-10-21&to=2026-10-21&size=1").expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(2)
        assertThat(body["totalPages"].asInt()).isEqualTo(2)
        assertThat(body["items"][0]["status"].asText()).isEqualTo("REFUNDED")
        val two = json(get("/payments/orders?from=2026-10-21&to=2026-10-21&size=1&page=2").expectStatus().isOk)
        assertThat(two["items"][0]["orderId"].asText()).isEqualTo(included.publicId.toString())
        assertThat(two["items"][0]["member"]["isAdmin"].asBoolean()).isTrue()
        assertThat(two["items"][0]["refundedAt"].isNull).isTrue()
        assertThat(two["items"][0]["reversalShortfall"].isNull).isTrue()
        assertThat(two["items"][0].has("userId")).isFalse()
        val only = json(get("/payments/orders?status=REFUNDED").expectStatus().isOk)
        assertThat(only["totalElements"].asLong()).isEqualTo(1)
        assertThat(json(get("/payments/orders?page=99").expectStatus().isOk)["items"].size()).isZero()
    }

    @Test fun `결제 정렬 네 열은 방향과 UUID 동률 및 NULL 후순위를 지킨다`() {
        val low = order(id = UUID.fromString("00000000-0000-4000-8000-000000000001"), refund = now,
            status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED)
        val high = order(id = UUID.fromString("00000000-0000-4000-8000-000000000002"), refund = now,
            status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED)
        for (sort in listOf("priceKrw", "creditAmount", "completedAt", "refundedAt")) {
            for (direction in listOf("asc", "desc")) {
                val first = json(get("/payments/orders?sort=$sort&order=$direction&size=1").expectStatus().isOk)
                val second = json(get("/payments/orders?sort=$sort&order=$direction&size=1&page=2").expectStatus().isOk)
                assertThat(first["items"][0]["orderId"].asText()).isEqualTo(low.publicId.toString())
                assertThat(second["items"][0]["orderId"].asText()).isEqualTo(high.publicId.toString())
            }
        }
        val earlierRefund = order(refund = now.minusSeconds(60), status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED)
        assertThat(json(get("/payments/orders?sort=refundedAt&order=asc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(earlierRefund.publicId.toString())
        assertThat(json(get("/payments/orders?sort=refundedAt&order=desc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(low.publicId.toString())
        orders.delete(earlierRefund)
        val distinct = order(price = 100, credit = 200, completed = midnight.minusSeconds(1))
        for (sort in listOf("priceKrw", "creditAmount", "completedAt")) {
            assertThat(json(get("/payments/orders?sort=$sort&order=asc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(distinct.publicId.toString())
            assertThat(json(get("/payments/orders?sort=$sort&order=desc").expectStatus().isOk)["items"][0]["orderId"].asText()).isEqualTo(low.publicId.toString())
        }
        for (direction in listOf("asc", "desc")) {
            assertThat(json(get("/payments/orders?sort=refundedAt&order=$direction").expectStatus().isOk)["items"][2]["orderId"].asText()).isEqualTo(distinct.publicId.toString())
        }
    }

    @Test fun `회원 상세 잔액은 만료 로트를 빼고 콘텐츠 수는 삭제된 행을 뺀다`() {
        val user = member("잔액 대상")
        lots.save(com.knk.manyak.credit.entity.CreditLot(userId = user.id, originalAmount = 300, remaining = 200, expiresAt = now.plusSeconds(1)))
        lots.save(com.knk.manyak.credit.entity.CreditLot(userId = user.id, originalAmount = 50, remaining = 50, expiresAt = now))
        val live = storyRepository.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "보유 스토리"))
        storyRepository.save(com.knk.manyak.story.entity.Story(userId = user.id, title = "삭제 스토리", deletedAt = now))
        chatRepository.save(com.knk.manyak.chat.entity.StoryChat(userId = user.id, storyId = live.id))
        chatRepository.save(com.knk.manyak.chat.entity.StoryChat(userId = user.id, storyId = live.id, deletedAt = now))
        val body = json(get("/members/${user.publicId}").expectStatus().isOk)
        assertThat(body["creditBalance"].asLong()).isEqualTo(200)
        assertThat(body["storyCount"].asLong()).isEqualTo(1)
        assertThat(body["chatCount"].asLong()).isEqualTo(1)
    }

    @Test fun `실제 회원 조회도 접속 기록 저장 실패면 개인정보 응답 대신 500이다`() {
        val user = member("개인정보")
        org.mockito.Mockito.doThrow(org.springframework.dao.DataIntegrityViolationException("injected"))
            .`when`(auditRepository).save(org.mockito.ArgumentMatchers.any(com.knk.manyak.admin.entity.AdminAuditLog::class.java)
                ?: com.knk.manyak.admin.entity.AdminAuditLog(adminUserId = admin.id,
                    category = com.knk.manyak.admin.entity.AdminAuditCategory.ACCESS,
                    action = com.knk.manyak.admin.entity.AdminAuditAction.MEMBER_VIEWED,
                    targetType = com.knk.manyak.admin.entity.AdminAuditTargetType.MEMBER, targetId = "fixture", requestId = "fixture"))
        get("/members?q=개인정보").expectStatus().is5xxServerError.expectBody().jsonPath("$.items").doesNotExist()
        get("/members/${user.publicId}").expectStatus().is5xxServerError.expectBody().jsonPath("$.memberId").doesNotExist()
        get("/members/${user.publicId}/transactions").expectStatus().is5xxServerError.expectBody().jsonPath("$.items").doesNotExist()
        get("/members/${user.publicId}/attendance").expectStatus().is5xxServerError.expectBody().jsonPath("$.items").doesNotExist()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `감사 로그는 현재 처리자 요약과 JSON 전후 값 및 필터를 페이지로 반환한다`() {
        get("/members/${admin.publicId}").expectStatus().isOk
        val target = UUID.randomUUID().toString()
        org.slf4j.MDC.put("request_id", "req_fixture")
        try {
            org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                audits.recordChange(admin.id, com.knk.manyak.admin.entity.AdminAuditAction.CREDIT_GRANTED, target, "테스트",
                    mapper.readTree("{\"balance\":1}"), mapper.readTree("{\"balance\":2}"))
            }
        } finally { org.slf4j.MDC.clear() }
        val request = "/audit-logs?adminId=${admin.publicId}&action=CREDIT_GRANTED&targetType=MEMBER&targetId=$target&size=1"
        val body = json(get(request).expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(1)
        assertThat(body["items"][0]["before"]["balance"].asInt()).isEqualTo(1)
        assertThat(body["items"][0]["after"]["balance"].asInt()).isEqualTo(2)
        assertThat(body["items"][0]["admin"]["memberId"].asText()).isEqualTo(admin.publicId.toString())
        assertThat(body["items"][0]["admin"].has("id")).isFalse()
        assertThat(body["items"][0]["requestId"].asText()).isEqualTo("req_fixture")
        for (filter in listOf("adminId=${UUID.randomUUID()}", "action=NOTICE_CREATED", "targetType=NOTICE", "targetType=MEMBER&targetId=missing")) {
            assertThat(json(get("/audit-logs?$filter").expectStatus().isOk)["totalElements"].asLong()).isZero()
        }
        assertThat(json(get("$request&page=2").expectStatus().isOk)["items"].size()).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isEqualTo(2)
    }

    @Test fun `대시보드는 KST 경계와 사유별 합계를 지키고 팀과 게스트를 구분한다`() {
        val ordinary = member("일반", created = midnight.plusSeconds(60))
        val team = member("팀", created = midnight.plusSeconds(60))
        val before = member("어제", created = midnight.minusSeconds(60))
        // 테스트 관리자도 일반 집계에서 제외해 각 집계의 기대값을 명확히 한다.
        val excluded = listOf(team.id, admin.id)
        for (owner in listOf<Long?>(ordinary.id, team.id, null)) {
            val story = storyRepository.save(com.knk.manyak.story.entity.Story(userId = owner, title = "집계", createdAt = midnight, deletedAt = now))
            val chat = chatRepository.save(com.knk.manyak.chat.entity.StoryChat(userId = owner, storyId = story.id, createdAt = midnight, deletedAt = now))
            repeat(3) { n ->
                val message = messageRepository.save(com.knk.manyak.chat.entity.StoryMessage(chatId = chat.id,
                    role = com.knk.manyak.chat.entity.MessageRole.ASSISTANT, content = "응답", messageOrder = n + 1, createdAt = midnight))
                if (n == 0) repeat(2) { v -> versions.save(com.knk.manyak.chat.entity.StoryMessageVersion(
                    messageId = message.id, versionNumber = v + 1, content = "이전 응답", choices = "[]")) }
            }
            messageRepository.save(com.knk.manyak.chat.entity.StoryMessage(chatId = chat.id,
                role = com.knk.manyak.chat.entity.MessageRole.USER, content = "입력", messageOrder = 4, createdAt = midnight))
        }
        for (owner in listOf(ordinary, team)) {
            for ((amount, reason) in listOf(250L to "ATTENDANCE_REWARD", 5000L to "PURCHASE", 100L to "SIGNUP_REWARD",
                -20L to "CHAT_TURN", -30L to "EXPIRE", 20L to "REFUND")) {
                jdbc.update("INSERT INTO credit_transactions(user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)", owner.id, amount, reason, java.sql.Timestamp.from(midnight))
            }
            order(user = owner, completed = midnight, price = 4800)
            order(user = owner, completed = midnight.minusSeconds(1), price = 2000, status = com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, refund = now)
        }
        val body = dashboardRepository.summary(now, midnight, excluded)
        assertThat(body.asOf).isEqualTo(now)
        assertThat(body.todayStartsAt).isEqualTo(midnight)
        assertThat(body.members.total).isEqualTo(2)
        assertThat(body.members.joinedToday).isEqualTo(1)
        assertThat(body.chats.startedTotal).isEqualTo(2)
        assertThat(body.chats.turnsTotal).isEqualTo(6)
        assertThat(body.chats.turnsToday).isEqualTo(6)
        assertThat(body.stories.total).isEqualTo(2)
        assertThat(body.credits.grantedTotal).isEqualTo(5350)
        assertThat(body.credits.spentTotal).isEqualTo(20)
        assertThat(body.credits.grantedToday).isEqualTo(5350)
        assertThat(body.credits.spentToday).isEqualTo(20)
        assertThat(body.stories.createdToday).isEqualTo(2)
        assertThat(body.chats.startedToday).isEqualTo(2)
        assertThat(body.payments.completedTotal).isEqualTo(2)
        assertThat(body.payments.completedToday).isEqualTo(1)
        assertThat(body.payments.amountKrwTotal).isEqualTo(6800)
        assertThat(body.payments.amountKrwToday).isEqualTo(4800)
        assertThat(dashboardRepository.summary(now, midnight, emptyList()).chats.startedTotal).isEqualTo(3)
        // 팀 계정 제외는 대시보드에만 적용하고 회원 검색에는 적용하지 않는다.
        assertThat(json(get("/members?q=팀").expectStatus().isOk)["totalElements"].asLong()).isEqualTo(1)
    }

    @Test fun `대시보드는 삭제된 카드를 응답하지 않고 활성과 정지 회원을 센다`() {
        member("정지", UserStatus.SUSPENDED)
        member("추가 활성")
        member("탈퇴", UserStatus.DELETED)
        val body = json(get("/dashboard/summary").expectStatus().isOk)
        assertThat(body["members"]["total"].asLong()).isEqualTo(3)
        assertThat(body["members"].has("withdrawnTotal")).isFalse()
        assertThat(body["members"].has("withdrawnToday")).isFalse()
        assertThat(body["payments"].has("refundedTotal")).isFalse()
        assertThat(body["payments"].has("refundedToday")).isFalse()
        assertThat(body.has("moderation")).isFalse()
        assertThat(body["asOf"].asText()).isEqualTo(now.toString())
        assertThat(body["todayStartsAt"].asText()).endsWith("15:00:00Z")
    }

    @Test fun `회원 검색은 정규화 닉네임과 공개 식별자와 정확한 활성 연동 이메일을 지원한다`() {
        val user = member("E Q U A L")
        socials.save(SocialAccount(userId = user.id, provider = SocialProvider.GOOGLE, providerUserId = "fixture", email = "lookup@example.test"))
        for (query in listOf("equal", "E Q U A L", user.publicId.toString(), "LoOkUp@EXAMPLE.test")) {
            val body = json(get("/members?q=$query").expectStatus().isOk)
            assertThat(body["items"].size()).isEqualTo(1)
            assertThat(body["items"][0]["memberId"].asText()).isEqualTo(user.publicId.toString())
            assertThat(body["items"][0].has("email")).isFalse()
            assertThat(body["items"][0].has("id")).isFalse()
            assertThat(body["page"].asInt()).isEqualTo(1)
            assertThat(body["size"].asInt()).isEqualTo(20)
        }
        assertThat(json(get("/members?q=lookup@example").expectStatus().isOk)["totalElements"].asLong()).isZero()
        val rows = jdbc.queryForList("SELECT * FROM admin_audit_logs ORDER BY id")
        assertThat(rows).hasSize(5)
        assertThat(rows.first()["admin_user_id"]).isEqualTo(admin.id)
        assertThat(rows.first()["action"]).isEqualTo("MEMBER_SEARCHED")
        assertThat(rows.first()["target_id"]).isEqualTo("equal")
        assertThat(json(get("/members?q=${UUID.randomUUID()}").expectStatus().isOk)["totalElements"].asLong()).isZero()
    }

    @Test fun `회원 상태 필터와 공개 식별자 동률 정렬 및 페이지 경계를 적용한다`() {
        val low = member("동률", UserStatus.SUSPENDED, UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val high = member("동률", UserStatus.SUSPENDED, UUID.fromString("00000000-0000-4000-8000-000000000002"))
        for (order in listOf("asc", "desc")) {
            val one = json(get("/members?q=동률&status=SUSPENDED&sort=createdAt&order=$order&size=1").expectStatus().isOk)
            assertThat(one["items"][0]["memberId"].asText()).isEqualTo(low.publicId.toString())
            assertThat(one["totalElements"].asLong()).isEqualTo(2)
            assertThat(one["totalPages"].asInt()).isEqualTo(2)
            val two = json(get("/members?q=동률&status=SUSPENDED&sort=createdAt&order=$order&size=1&page=2").expectStatus().isOk)
            assertThat(two["items"][0]["memberId"].asText()).isEqualTo(high.publicId.toString())
        }
        assertThat(json(get("/members?q=동률&page=99").expectStatus().isOk)["items"].size()).isZero()
    }

    @Test fun `탈퇴 회원은 닉네임 검색에서 숨기고 UUID와 상태 필터로 찾는다`() {
        val user = member("알 수 없음", UserStatus.DELETED)
        assertThat(json(get("/members?q=알").expectStatus().isOk)["totalElements"].asLong()).isZero()
        assertThat(json(get("/members?q=${user.publicId}&status=DELETED").expectStatus().isOk)["items"][0]["status"].asText()).isEqualTo("DELETED")
    }

    @Test fun `상세는 로트 기반 잔액과 콘텐츠 수를 응답하고 접속 기록을 남긴다`() {
        val user = member("대상")
        val body = json(get("/members/${user.publicId}").expectStatus().isOk)
        assertThat(body["creditBalance"].asLong()).isZero()
        assertThat(body["storyCount"].asLong()).isZero()
        assertThat(body["chatCount"].asLong()).isZero()
        assertThat(body.has("email")).isFalse()
        assertThat(body["deletedAt"].isNull).isTrue()
        val row = jdbc.queryForMap("SELECT * FROM admin_audit_logs")
        assertThat(row["action"]).isEqualTo("MEMBER_VIEWED")
        assertThat(row["target_id"]).isEqualTo(user.publicId.toString())
        assertThat(row["reason"]).isNull()
        assertThat(row["before_state"]).isNull()
        assertThat(row["after_state"]).isNull()
        get("/members/not-a-uuid").expectStatus().isBadRequest.expectBody().jsonPath("$.details[0].field").isEqualTo("memberId")
        get("/members/${UUID.randomUUID()}").expectStatus().isNotFound
    }

    @Test fun `페이지 정렬 상태와 검색어의 잘못된 값은 400이다`() {
        for (query in listOf("page=0", "page=-1", "page=bad", "page=2147483648", "cursor=obsolete", "limit=20", "size=0", "size=101", "size=bad",
            "sort=unknown", "order=ASC", "status=UNKNOWN", "q=", "q=${"a".repeat(51)}")) {
            get("/members?${if (query.startsWith("q=")) query else "q=대상&$query"}").expectStatus().isBadRequest
        }
        for (query in listOf("sort=bad", "order=bad", "status=PENDING", "from=bad", "from=2026-02-30", "from=2026-02-02&to=2026-01-01")) {
            get("/payments/orders?$query").expectStatus().isBadRequest
        }
        for (query in listOf("adminId=bad", "targetId=alone", "size=101", "action=BAD", "targetType=BAD")) {
            get("/audit-logs?$query").expectStatus().isBadRequest
        }
    }

    @Test fun `차트는 빈 날짜와 고정 분포 및 링크를 반환하고 기간을 제한한다`() {
        for (days in listOf(7, 30, 90)) {
            val body = json(get("/dashboard/charts?days=$days").expectStatus().isOk)
            assertThat(body["daily"].size()).isEqualTo(days)
            assertThat(body["distributions"].size()).isEqualTo(8)
            val turns = body["distributions"][1]["points"]
            assertThat(turns.size()).isEqualTo(13)
            assertThat(turns[1]["link"]["params"]["turnBucket"].asText()).isEqualTo("1")
            assertThat(turns[1]["link"]["params"]["from"].asText()).isEqualTo(body["from"].asText())
            assertThat(body["daily"][0]["attendance"].asLong()).isZero()
        }
        get("/dashboard/charts?days=10").expectStatus().isBadRequest
    }

    @Test fun `스토리와 채팅방은 공개 식별자와 수치만 반환하며 유지율과 삭제를 처리한다`() {
        val story = storyRepository.save(com.knk.manyak.story.entity.Story(title = "차트 스토리", genre = "판타지, 미스터리"))
        val chats = listOf(0, 1, 5, 12).map { turns -> chatRepository.save(com.knk.manyak.chat.entity.StoryChat(
            storyId = story.id, currentTurn = turns, createdAt = midnight)) }
        val list = json(get("/stories?genre=판타지").expectStatus().isOk)
        assertThat(list["items"][0]["owner"].isNull).isTrue()
        assertThat(list["items"][0]["chatCount"].asLong()).isEqualTo(4)
        val detail = json(get("/stories/${story.publicId}").expectStatus().isOk)
        assertThat(detail["retention"][0]["rate"].asDouble()).isEqualTo(0.75)
        assertThat(detail["retention"][4]["rate"].asDouble()).isZero()
        assertThat(detail["recentChats"].size()).isEqualTo(4)
        val chat = json(get("/chats/${chats[0].publicId}").expectStatus().isOk)
        assertThat(chat["startedAs"].asText()).isEqualTo("GUEST")
        assertThat(chat.has("messages")).isFalse()
        assertThat(chat.has("persona")).isFalse()
        assertThat(json(get("/chats?turnBucket=12").expectStatus().isOk)["totalElements"].asLong()).isZero()
        val noGenre = storyRepository.save(com.knk.manyak.story.entity.Story(title = "장르 없음"))
        val noGenreChat = chatRepository.save(com.knk.manyak.chat.entity.StoryChat(storyId = noGenre.id))
        val genre = json(get("/stories/${noGenre.publicId}").expectStatus().isOk)["genre"]
        val chatGenre = json(get("/chats/${noGenreChat.publicId}").expectStatus().isOk)["story"]["genre"]
        assertThat(genre.isNull).isFalse()
        assertThat(chatGenre.isNull).isFalse()
        assertThat(genre.asText()).isEmpty()
        assertThat(chatGenre.asText()).isEmpty()
        val deleted = storyRepository.save(com.knk.manyak.story.entity.Story(title = "삭제", deletedAt = now))
        get("/stories/${deleted.publicId}").expectStatus().isNotFound
        for (path in listOf("/stories/bad", "/chats/bad", "/chats?startedAs=BOT", "/stories?from=2026-13-01")) {
            get(path).expectStatus().isBadRequest
        }
    }

    private fun credit(user: User, amount: Long, reason: String, at: Instant = now) {
        jdbc.update("INSERT INTO credit_transactions(user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
            user.id, amount, reason, java.sql.Timestamp.from(at))
    }

    @Test fun `전체 이프 거래는 최신순 동시각 역기록순으로 페이지하고 공개 회원 요약만 반환한다`() {
        val active = member("거래 회원")
        val deleted = member("탈퇴 회원", UserStatus.DELETED)
        val suspended = member("정지 회원", UserStatus.SUSPENDED)
        credit(active, 100, "SIGNUP_REWARD", midnight)
        credit(deleted, -20, "CHAT_TURN")
        credit(suspended, 30, "REFUND")
        val page = json(get("/credits/transactions?size=2").expectStatus().isOk)
        assertThat(page["page"].asInt()).isEqualTo(1)
        assertThat(page["size"].asInt()).isEqualTo(2)
        assertThat(page["totalElements"].asLong()).isEqualTo(3)
        assertThat(page["totalPages"].asLong()).isEqualTo(2)
        assertThat(page["items"].toList().map { it["amount"].asLong() }).containsExactly(30L, -20L)
        assertThat(page["items"][0]["member"]["memberId"].asText()).isEqualTo(suspended.publicId.toString())
        assertThat(page["items"][0]["member"]["nickname"].asText()).isEqualTo(suspended.nickname)
        assertThat(page["items"][0]["member"]["status"].asText()).isEqualTo("SUSPENDED")
        assertThat(page["items"][1]["member"]["status"].asText()).isEqualTo("DELETED")
        assertThat(page["items"][0]["createdAt"].asText()).isEqualTo(now.toString())
        assertThat(page["items"][0]["reason"].asText()).isEqualTo("REFUND")
        for (item in page["items"]) {
            assertThat(item.properties().map { it.key }).containsExactlyInAnyOrder("amount", "reason", "createdAt", "member")
            assertThat(item["member"].properties().map { it.key }).containsExactlyInAnyOrder("memberId", "nickname", "status", "isAdmin", "createdAt")
        }
        val next = json(get("/credits/transactions?page=2&size=2&sort=createdAt&order=desc").expectStatus().isOk)
        assertThat(next["items"].toList().map { it["amount"].asLong() }).containsExactly(100L)
        assertThat(next["items"][0]["member"]["createdAt"].asText()).isEqualTo(active.createdAt.toString())
        assertThat(next["items"][0]["member"]["isAdmin"].asBoolean()).isFalse()
        val ascending = json(get("/credits/transactions?order=asc").expectStatus().isOk)
        assertThat(ascending["items"].toList().map { it["amount"].asLong() }).containsExactly(100L, 30L, -20L)
        assertThat(json(get("/credits/transactions?page=2147483647&size=100").expectStatus().isOk)["items"].size()).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `전체 이프 거래의 방향은 요약과 같고 KST 날짜를 필터한다`() {
        val owner = member("조건 회원")
        val other = member("다른 회원")
        for (reason in listOf("SIGNUP_REWARD", "INVITE_REWARD", "ATTENDANCE_REWARD", "PURCHASE", "ADMIN_GRANT")) credit(owner, 10, reason, midnight)
        for (reason in listOf("STORY_CREATION", "CHAT_TURN", "CHAT_IMAGE")) credit(owner, -5, reason, midnight.plusSeconds(86399))
        for ((amount, reason) in listOf(20L to "REFUND", -20L to "EXPIRE", -20L to "ADMIN_REVOKE", -20L to "PURCHASE_REVERSAL",
            -10L to "SIGNUP_REWARD", 5L to "CHAT_TURN")) credit(owner, amount, reason)
        credit(owner, 10, "SIGNUP_REWARD", midnight.minusSeconds(1))
        credit(owner, -5, "CHAT_TURN", midnight.plusSeconds(86400))
        credit(other, 10, "ADMIN_GRANT", midnight)
        fun count(query: String) = json(get("/credits/transactions?$query").expectStatus().isOk)["totalElements"].asLong()
        assertThat(count("direction=granted&date=2026-10-21")).isEqualTo(6)
        assertThat(count("direction=spent&date=2026-10-21")).isEqualTo(3)
        assertThat(count("date=2026-10-21")).isEqualTo(15)
        assertThat(count("direction=spent")).isEqualTo(4)
        assertThat(count("date=2026-10-23")).isZero()
        val empty = json(get("/credits/transactions?date=2026-10-23").expectStatus().isOk)
        assertThat(empty["items"].size()).isZero()
        assertThat(empty["totalPages"].asLong()).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Long::class.java)).isZero()
    }

    @Test fun `전체 이프 거래는 관리자 인증과 쿼리 검증을 적용한다`() {
        client.get().uri("/api/v1/admin/credits/transactions").exchange().expectStatus().isUnauthorized
        val ordinary = member("일반 회원")
        client.get().uri("/api/v1/admin/credits/transactions").header("Authorization", "Bearer ${tokens.issueAdminToken(ordinary.publicId)}")
            .exchange().expectStatus().isForbidden
        for (query in listOf("direction=other", "direction=", "date=2026-02-30", "date=2026-2-01", "date=bad",
            "sort=amount", "order=bad", "page=0", "size=101", "size=0", "page=bad", "cursor=bad", "limit=20")) {
            val error = json(get("/credits/transactions?$query").expectStatus().isBadRequest)
            assertThat(error["code"].asText()).isEqualTo("BAD_REQUEST")
        }
    }

    @Test fun `회원 거래 페이지는 누계만 공개하고 출석은 KST 날짜별이며 상세 접속 기록을 남긴다`() {
        val user = member("거래")
        for ((amount, reason, instant) in listOf(Triple(250L, "ATTENDANCE_REWARD", midnight),
            Triple(-20L, "CHAT_TURN", now), Triple(-30L, "CHAT_IMAGE", now))) {
            jdbc.update("INSERT INTO credit_transactions(user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
                user.id, amount, reason, java.sql.Timestamp.from(instant))
        }
        val body = json(get("/members/${user.publicId}/transactions?size=2").expectStatus().isOk)
        assertThat(body["totalPages"].asLong()).isEqualTo(2)
        assertThat(body["items"][0]["balanceAfter"].asLong()).isEqualTo(230)
        assertThat(body["items"][0].has("id")).isFalse()
        val attendance = json(get("/members/${user.publicId}/attendance").expectStatus().isOk)
        assertThat(attendance["items"][0]["date"].asText()).isEqualTo("2026-10-21")
        assertThat(attendance["items"][0]["creditAmount"].asLong()).isEqualTo(250)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action = 'MEMBER_VIEWED'", Long::class.java)).isEqualTo(2)
    }

    @Test fun `결제 상세는 환불된 첫 결제도 기준으로 삼고 원장 전후를 계산한다`() {
        val user = member("결제")
        order(user, com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, midnight, refund = now)
        val later = order(user, completed = now)
        for ((amount, reason, instant) in listOf(Triple(-20L, "CHAT_TURN", midnight.minusSeconds(1)),
            Triple(-60L, "CHAT_TURN", midnight), Triple(20L, "REFUND", now))) {
            jdbc.update("INSERT INTO credit_transactions(user_id, amount, reason, created_at) VALUES (?, ?, ?, ?)",
                user.id, amount, reason, java.sql.Timestamp.from(instant))
        }
        val body = json(get("/payments/orders/${later.publicId}").expectStatus().isOk)
        assertThat(body["firstPaidAt"].asText()).isEqualTo(midnight.toString())
        assertThat(body["before"]["turns"].asLong()).isEqualTo(1)
        assertThat(body["after"]["creditsSpent"].asLong()).isEqualTo(40)
        val pending = order(user, com.knk.manyak.credit.entity.CreditOrderStatus.PENDING, null)
        get("/payments/orders/${pending.publicId}").expectStatus().isNotFound
    }

    @Autowired private lateinit var chartsRepository: com.knk.manyak.admin.repository.AdminChartsRepository
    @Autowired private lateinit var creationSessions: com.knk.manyak.story.repository.StoryCreationSessionRepository
    @Autowired private lateinit var creationCharacters: com.knk.manyak.story.repository.StoryCreationCharacterRepository
    @Autowired private lateinit var creationTags: com.knk.manyak.story.repository.StoryCreationTagRepository
    @Autowired private lateinit var sessionTags: com.knk.manyak.story.repository.StoryCreationSessionTagRepository
    @Autowired private lateinit var aiLogs: com.knk.manyak.global.observability.aicall.AiCallLogRepository
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private lateinit var official: com.knk.manyak.story.service.OfficialStoryAccount

    private fun story(user: User? = null, title: String = "분포", genre: String = "판타지, 미스터리") =
        storyRepository.save(com.knk.manyak.story.entity.Story(userId = user?.id, title = title, genre = genre, createdAt = midnight))
    private fun chat(story: com.knk.manyak.story.entity.Story, user: User? = null, turns: Int = 0, created: Instant = midnight) =
        chatRepository.save(com.knk.manyak.chat.entity.StoryChat(storyId = story.id, userId = user?.id, currentTurn = turns, createdAt = created))
    private fun ledger(user: User, amount: Long, reason: String, created: Instant = now, ref: Long? = null, refType: String? = null) {
        jdbc.update("INSERT INTO credit_transactions(user_id, amount, reason, created_at, ref_id, ref_type) VALUES (?, ?, ?, ?, ?, ?)",
            user.id, amount, reason, java.sql.Timestamp.from(created), ref, refType)
    }
    private fun message(chat: com.knk.manyak.chat.entity.StoryChat, seconds: Long, role: com.knk.manyak.chat.entity.MessageRole = com.knk.manyak.chat.entity.MessageRole.USER) =
        messageRepository.save(com.knk.manyak.chat.entity.StoryMessage(chatId = chat.id, role = role, content = "응답에 나오면 안 되는 원문",
            messageOrder = if (role == com.knk.manyak.chat.entity.MessageRole.USER) 1 else 2, createdAt = chat.createdAt.plusSeconds(seconds)))

    @Test fun `턴 구간 13개는 2에서 6까지 개별 집계하고 모든 경계의 목록 링크와 일치한다`() {
        val owner = member("턴 경계")
        val story = story()
        val turns = (0..7).toList() + listOf(9, 10, 14, 15, 19, 20, 29, 30, 49, 50, 51, Int.MAX_VALUE)
        val samples = turns.map { it to chat(story, turns = it) } + listOf(5, 6).map { it to chat(story, owner, it) }
        val cases = listOf("0" to (0..0), "1" to (1..1), "2" to (2..2), "3" to (3..3), "4" to (4..4),
            "5" to (5..5), "6" to (6..6), "7~9" to (7..9), "10~14" to (10..14), "15~19" to (15..19),
            "20~29" to (20..29), "30~49" to (30..49), "50+" to (50..Int.MAX_VALUE))
        val body = json(get("/dashboard/charts?days=7").expectStatus().isOk)
        val points = body["distributions"].first { it["chartId"].asText() == "turns" }["points"].toList()
        assertThat(points.map { it["label"].asText() }).isEqualTo(cases.map { it.first })
        points.forEachIndexed { index, point ->
            val expected = samples.filter { it.first in cases[index].second }.map { it.second.publicId.toString() }
            assertThat(point["value"].asInt()).isEqualTo(expected.size)
            val params = point["link"]["params"]
            assertThat(point["link"]["list"].asText()).isEqualTo("chats")
            assertThat(params["turnBucket"].asText()).isEqualTo(index.toString())
            val page = json(get("/chats?turnBucket=$index&from=${params["from"].asText()}&to=${params["to"].asText()}&size=100").expectStatus().isOk)
            assertThat(page["totalElements"].asInt()).isEqualTo(expected.size)
            assertThat(page["items"].toList().map { it["chatId"].asText() }).containsExactlyInAnyOrderElementsOf(expected)
        }
        for (bucket in listOf(5, 6)) for (startedAs in listOf("GUEST", "MEMBER")) {
            val page = json(get("/chats?turnBucket=$bucket&startedAs=$startedAs").expectStatus().isOk)
            assertThat(page["totalElements"].asInt()).isEqualTo(1)
            assertThat(page["items"][0]["turnCount"].asInt()).isEqualTo(bucket)
        }
        for (value in listOf("-1", "13", "3~4", "5~6", "1.5", "bad")) {
            get("/chats?turnBucket=$value").expectStatus().isBadRequest
        }
    }

    @Test fun `차트 분포는 다중 장르와 구간과 비율과 첫 메시지 통계를 계산하고 목록 링크와 일치한다`() {
        val owner = member("회원")
        val story = story()
        val chats = listOf(chat(story, turns = 0), chat(story, owner, 1), chat(story, owner, 4), chat(story, owner, 50))
        message(chats[0], 30); message(chats[1], 60); message(chats[2], 1000)
        val body = json(get("/dashboard/charts?days=7").expectStatus().isOk)
        val dist = body["distributions"]
        assertThat(dist.toList().map { listOf(it["chartId"].asText(), it["title"].asText(), it["description"].asText(), it["unit"].asText()) }).isEqualTo(listOf(
            listOf("genre-depth", "장르별 평균 턴 수", "채팅 생성일 기준 장르별 평균 현재 턴 수", "턴"),
            listOf("turns", "턴 수 분포", "채팅 생성일을 기준으로 구간별 채팅 수 집계", "개"),
            listOf("started-as", "게스트와 회원 비율", "현재 소유 회원 여부로 판정한 채팅 비율", "%"),
            listOf("member-chats", "회원별 채팅 생성 횟수", "기간에 채팅을 생성한 회원의 생성 횟수 분포", "명"),
            listOf("first-message", "첫 사용자 입력까지 걸린 시간", "채팅 생성일 기준, 900초 초과 1건, 입력 없음 1건 제외", "초"),
            listOf("ai-latency", "기능별 AI 평균 호출 시간", "호출 시간이 기록된 성공 호출의 평균 호출 시간", "초"),
            listOf("ai-success", "기능별 AI 성공률", "완료된 호출 중 성공 비율, 진행 중 제외", "%"),
            listOf("characters", "간편 제작별 입력 주변 인물 수", "미삭제 스토리의 최신 완료 간편 제작 생성일 기준 직접 입력한 주변 인물 수, 공식 계정 포함", "건"),
        ))
        assertThat(dist[0]["points"].size()).isEqualTo(2)
        assertThat(dist[0]["points"][0]["value"].asDouble()).isEqualTo(13.75)
        for (i in listOf(0, 1, 4, 12)) assertThat(dist[1]["points"][i]["value"].asDouble()).isEqualTo(1.0)
        assertThat(dist[2]["points"][0]["value"].asDouble()).isEqualTo(25.0)
        assertThat(dist[2]["points"][1]["value"].asDouble()).isEqualTo(75.0)
        assertThat(dist[3]["points"][2]["value"].asDouble()).isEqualTo(1.0)
        assertThat(dist[4]["points"][0]["value"].asDouble()).isEqualTo(45.0)
        assertThat(dist[4]["description"].asText()).contains("900초 초과 1", "입력 없음 1")
        for (point in dist[1]["points"]) {
            val p = point["link"]["params"]
            val list = json(get("/chats?turnBucket=${p["turnBucket"].asText()}&from=${p["from"].asText()}&to=${p["to"].asText()}").expectStatus().isOk)
            assertThat(list["totalElements"].asDouble()).isEqualTo(point["value"].asDouble())
        }
        val periodMember = json(get("/members?chatBucket=2&chatFrom=2026-10-21&chatTo=2026-10-21").expectStatus().isOk)
        assertThat(periodMember["items"][0]["periodChatCount"].asInt()).isEqualTo(3)
    }

    @Test fun `삭제된 스토리의 채팅도 분포에 포함하고 모든 개수 막대는 링크 목록 총계와 일치한다`() {
        val live = story(genre = "유지 장르")
        val removed = story(genre = "삭제 장르")
        jdbc.update("UPDATE stories SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), removed.id)
        val turns = listOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 15, 20, 30, 50)
        val included = mutableListOf<com.knk.manyak.chat.entity.StoryChat>()
        for ((index, count) in listOf(1, 2, 3, 5, 10).withIndex()) {
            val owner = member("채팅 수 $count")
            repeat(count) { included += chat(removed, owner, turns[included.size % turns.size]) }
            if (index == 0) chat(removed, owner, 50, midnight.minusSeconds(10 * 86400))
        }
        included += chat(removed, turns = 0)
        included += chat(live, turns = 30)
        val deletedChat = chat(removed, turns = 50)
        jdbc.update("UPDATE story_chats SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), deletedChat.id)
        message(included.first(), 60)
        message(included.last(), 30)

        val body = json(get("/dashboard/charts?days=7").expectStatus().isOk)
        val distributions = body["distributions"].associateBy { it["chartId"].asText() }
        fun linkedTotal(point: JsonNode): Long {
            val link = point["link"]
            val query = link["params"].properties().joinToString("&") { (key, value) ->
                key + "=" + java.net.URLEncoder.encode(value.asText(), Charsets.UTF_8)
            }
            return json(get("/${link["list"].asText()}?$query").expectStatus().isOk)["totalElements"].asLong()
        }
        for ((id, size) in listOf("turns" to 13, "member-chats" to 5)) {
            val points = distributions.getValue(id)["points"]
            assertThat(points.size()).isEqualTo(size)
            for (point in points) {
                assertThat(point["value"].asDouble()).describedAs("%s %s", id, point["label"].asText())
                    .isEqualTo(linkedTotal(point).toDouble())
            }
        }
        assertThat(distributions.getValue("turns")["points"].sumOf { it["value"].asLong() }).isEqualTo(included.size.toLong())
        assertThat(distributions.getValue("member-chats")["points"].toList().map { it["value"].asLong() }).containsExactly(1L, 1L, 1L, 1L, 1L)
        val ratios = distributions.getValue("started-as")["points"]
        for (point in ratios) {
            val expected = java.math.BigDecimal.valueOf(100.0 * linkedTotal(point) / included.size)
                .setScale(2, java.math.RoundingMode.HALF_UP).toDouble()
            assertThat(point["value"].asDouble()).isEqualTo(expected)
        }
        val genres = distributions.getValue("genre-depth")["points"].associateBy { it["label"].asText() }
        assertThat(genres).doesNotContainKey("삭제 장르")
        assertThat(genres.getValue("유지 장르")["value"].asDouble()).isEqualTo(30.0)
        val waits = distributions.getValue("first-message")
        assertThat(waits["points"].toList().map { it["value"].asDouble() }).containsExactly(45.0, 45.0, 52.5, 57.0)
        assertThat(waits["description"].asText()).contains("900초 초과 0", "입력 없음 ${included.size - 2}건")
    }

    @Test fun `장르 깊이는 삭제 스토리를 빼고 링크 스토리 목록의 턴 합과 채팅 수로 가중 평균한다`() {
        val first = story(genre = "판타지, 미스터리")
        val second = story(genre = "판타지")
        val third = story(genre = "미스터리")
        val removed = story(genre = "판타지, 삭제 전용")
        jdbc.update("UPDATE stories SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), removed.id)
        chat(first, turns = 2); chat(first, turns = 4)
        chat(second, turns = 12); chat(third, turns = 8)
        chat(removed, turns = 100)
        story(genre = "판타지") // 기간에 채팅이 없는 스토리는 링크 목록에서도 제외한다.
        chat(first, turns = 999, created = midnight.minusSeconds(10 * 86400))
        val deletedChat = chat(first, turns = 999)
        jdbc.update("UPDATE story_chats SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), deletedChat.id)

        val body = json(get("/dashboard/charts?days=7").expectStatus().isOk)
        val points = body["distributions"].first { it["chartId"].asText() == "genre-depth" }["points"].toList()
        assertThat(points.map { it["label"].asText() }).containsExactlyInAnyOrder("판타지", "미스터리")
        for (point in points) {
            val link = point["link"]
            assertThat(link["list"].asText()).isEqualTo("stories")
            val response = client.get().uri { uri ->
                uri.path("/api/v1/admin/stories")
                link["params"].properties().forEach { (key, value) -> uri.queryParam(key, value.asText()) }
                uri.build()
            }.header("Authorization", "Bearer $token").exchange()
            val page = json(response.expectStatus().isOk)
            assertThat(page["totalElements"].asLong()).isEqualTo(2)
            assertThat(page["items"].size()).isEqualTo(2)
            assertThat(page["items"].toList().map { it["storyId"].asText() }).doesNotContain(removed.publicId.toString())
            val totalTurns = page["items"].sumOf { it["totalTurns"].asLong() }
            val chatCount = page["items"].sumOf { it["chatCount"].asLong() }
            assertThat(chatCount).isEqualTo(3)
            assertThat(totalTurns).isEqualTo(if (point["label"].asText() == "판타지") 18 else 14)
            assertThat(point["value"].asDouble()).isEqualTo(totalTurns.toDouble() / chatCount)
        }
    }

    @Test fun `채팅 필터와 소모는 원문을 읽지 않고 첫 입력과 턴 날짜 및 AI 기록을 따른다`() {
        val owner = member("활동")
        val story = story(owner)
        val fast = chat(story, owner, 51); val slow = chat(story); val silent = chat(story)
        message(fast, 30); message(slow, 901)
        message(fast, 40, com.knk.manyak.chat.entity.MessageRole.ASSISTANT)
        ledger(owner, -20, "CHAT_TURN", ref = fast.id, refType = "CHAT_TURN")
        ledger(owner, -20, "CHAT_TURN", ref = fast.id, refType = "CHAT")
        ledger(owner, -5, "CHAT_IMAGE", ref = fast.id, refType = "CHAT_IMAGE")
        ledger(owner, -999, "CHAT_TURN", ref = silent.id, refType = "CHAT")
        aiLogs.save(com.knk.manyak.global.observability.aicall.AiCallLog(requestId = "chat-filter", callerService = "test",
            feature = com.knk.manyak.global.observability.aicall.AiCallFeature.CHAT_RESPONSE, chatId = fast.publicId, status = com.knk.manyak.global.observability.aicall.AiCallStatus.SUCCEEDED,
            latencyMs = 1200, createdAt = now))
        val body = json(get("/chats?memberId=${owner.publicId}&storyId=${story.publicId}&startedAs=MEMBER&turnBucket=12&hasFirstMessage=true&turnDate=2026-10-21&aiFeature=chat_response&aiStatus=FINISHED&hasLatency=true&aiFrom=2026-10-21&aiTo=2026-10-21").expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(1)
        assertThat(body["items"][0]["creditsSpent"].asLong()).isEqualTo(45)
        assertThat(body["items"][0]["firstMessageSeconds"].asLong()).isEqualTo(30)
        assertThat(body.toString()).doesNotContain("응답에 나오면", "suggestedInputs", "summary", "persona")
        assertThat(json(get("/chats?hasFirstMessage=false").expectStatus().isOk)["totalElements"].asLong()).isEqualTo(2)
        for (q in listOf("활동", "분포", fast.publicId.toString())) {
            assertThat(json(get("/chats?q=$q").expectStatus().isOk)["totalElements"].asLong()).isGreaterThan(0)
        }
        assertThat(json(get("/chats?turnDate=2026-10-20").expectStatus().isOk)["items"].size()).isZero()
    }

    @Test fun `회원 확장과 필터는 완료 결제와 활동 및 사유 원장을 조회하고 NULL은 항상 마지막이다`() {
        val owner = member("소비자"); val inactive = member("활동없음")
        val story = story(owner); chat(story, owner); chat(story, owner)
        ledger(owner, -40, "CHAT_TURN"); ledger(owner, 250, "ATTENDANCE_REWARD")
        order(owner, price = 4800); order(owner, com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, price = 2000, refund = now)
        val body = json(get("/members?q=${owner.publicId}&creditDirection=spent&creditDate=2026-10-21&attendanceDate=2026-10-21&from=2026-01-01&to=2026-01-01").expectStatus().isOk)["items"][0]
        assertThat(body["creditsSpent"].asLong()).isEqualTo(40)
        assertThat(body["paidKrw"].asLong()).isEqualTo(4800)
        assertThat(body["chatCount"].asLong()).isEqualTo(2)
        assertThat(body["lastActiveAt"].isNull).isFalse()
        assertThat(body["periodChatCount"].isNull).isTrue()
        for (direction in listOf("asc", "desc")) {
            val rows = json(get("/members?sort=lastActiveAt&order=$direction").expectStatus().isOk)["items"].toList()
            assertThat(rows.indexOfFirst { it["memberId"].asText() == owner.publicId.toString() }).isLessThan(rows.indexOfFirst { it["memberId"].asText() == inactive.publicId.toString() })
        }
        member("탈퇴", UserStatus.DELETED)
        assertThat(json(get("/members?excludeDeleted=true").expectStatus().isOk)["items"].toList().any { it["status"].asText() == "DELETED" }).isFalse()
        get("/members?creditDate=2026-10-21").expectStatus().isBadRequest
    }

    @Test fun `결제 검색과 감사 검색 날짜 분류 정렬은 모든 조건을 AND로 적용한다`() {
        val owner = member("검색 소유자"); val payment = order(owner)
        for (q in listOf("검색", owner.publicId.toString(), payment.publicId.toString())) {
            assertThat(json(get("/payments/orders?q=$q&memberId=${owner.publicId}&status=COMPLETED").expectStatus().isOk)["totalElements"].asLong()).isEqualTo(1)
        }
        assertThat(json(get("/payments/orders?memberId=${admin.publicId}").expectStatus().isOk)["totalElements"].asLong()).isZero()
        get("/members/${owner.publicId}").expectStatus().isOk
        // 감사 로그 생성 시각은 실제 시계이므로 조회 날짜는 별도로 고정한다.
        jdbc.update("UPDATE admin_audit_logs SET created_at=?", java.sql.Timestamp.from(now))
        assertThat(json(get("/audit-logs?category=ACCESS&q=MEMBER&from=2026-10-21&to=2026-10-21&sort=admin&order=asc").expectStatus().isOk)["totalElements"].asLong()).isEqualTo(1)
        assertThat(json(get("/audit-logs?category=CHANGE").expectStatus().isOk)["totalElements"].asLong()).isZero()
        assertThat(json(get("/audit-logs?action=ORIGINAL_UPDATED&targetType=STORY").expectStatus().isOk)["totalElements"].asLong()).isZero()
        for (q in listOf("category=READ", "sort=reason", "from=bad")) get("/audit-logs?$q").expectStatus().isBadRequest
    }

    @Test fun `AI 차트는 성공 지연과 종료 성공률을 계산하고 채팅 소유자 및 제작 스토리 소유자로 팀을 제외한다`() {
        val owner = member("일반"); val team = member("팀")
        val normalStory = story(owner); val teamStory = story(team)
        val normalChat = chat(normalStory, owner); val teamChat = chat(teamStory, team)
        fun ai(feature: com.knk.manyak.global.observability.aicall.AiCallFeature, status: com.knk.manyak.global.observability.aicall.AiCallStatus,
            latency: Long?, chatId: UUID? = null, storyId: Long? = null) = aiLogs.save(com.knk.manyak.global.observability.aicall.AiCallLog(
                requestId = UUID.randomUUID().toString(), callerService = "test", feature = feature, chatId = chatId, storyId = storyId, status = status, latencyMs = latency, createdAt = now))
        val chatFeature = com.knk.manyak.global.observability.aicall.AiCallFeature.CHAT_RESPONSE
        val creationFeature = com.knk.manyak.global.observability.aicall.AiCallFeature.STORY_COMPLETION
        val success = com.knk.manyak.global.observability.aicall.AiCallStatus.SUCCEEDED
        ai(chatFeature, success, 1000, normalChat.publicId, teamStory.id)
        ai(chatFeature, success, 3000, normalChat.publicId)
        ai(chatFeature, com.knk.manyak.global.observability.aicall.AiCallStatus.FAILED, 4000, normalChat.publicId)
        ai(chatFeature, com.knk.manyak.global.observability.aicall.AiCallStatus.STARTED, null, normalChat.publicId)
        ai(chatFeature, success, 900000, teamChat.publicId)
        ai(com.knk.manyak.global.observability.aicall.AiCallFeature.CHOICE_GENERATION, success, 1234, normalChat.publicId)
        ai(creationFeature, success, 2000, storyId = normalStory.id)
        ai(creationFeature, success, 4000) // 스토리 연결 전 제작 호출도 포함
        ai(creationFeature, success, 900000, storyId = teamStory.id)
        val service = com.knk.manyak.admin.service.AdminChartsService(chartsRepository,
            com.knk.manyak.admin.config.AdminDashboardProperties(listOf(team.id, admin.id)), official,
            java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        val body = service.charts(7)
        assertThat(body.distributions[5].points[0].value).isEqualTo(2.0)
        assertThat(body.distributions[6].points[0].value).isEqualTo(66.67)
        assertThat(body.distributions[5].points[1].value).isEqualTo(1.234)
        assertThat(body.distributions[5].points[3].value).isEqualTo(3.0)
        assertThat(body.daily.last().chats).isEqualTo(1)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.knk.manyak.story.entity.StoryCreationTagSource::class)
    fun `입력 인물은 이름 성별 특징 중 하나라도 있고 빈 칸은 0명으로 집계한다`(source: com.knk.manyak.story.entity.StoryCreationTagSource) {
        fun session(target: com.knk.manyak.story.entity.Story) = creationSessions.save(
            com.knk.manyak.story.entity.StoryCreationSession(storyId = target.id,
                status = com.knk.manyak.story.entity.StoryCreationSessionStatus.STORY_CREATED, createdAt = midnight))
        val orders = mutableMapOf<Pair<Long, com.knk.manyak.story.entity.StoryCreationCharacterRole>, Int>()
        fun character(session: com.knk.manyak.story.entity.StoryCreationSession, name: String? = null,
                      gender: com.knk.manyak.story.dto.SimpleStoryCharacterGender? = null,
                      role: com.knk.manyak.story.entity.StoryCreationCharacterRole = com.knk.manyak.story.entity.StoryCreationCharacterRole.SUPPORTING_CHARACTER) =
            creationCharacters.save(com.knk.manyak.story.entity.StoryCreationCharacter(
                creationSession = session, role = role, name = name, gender = gender,
                sortOrder = orders.merge(session.id to role, 1, Int::plus)!!.toShort()))
        val mixedStory = story(title = "직접 입력 3명")
        val mixed = session(mixedStory)
        character(mixed)
        character(mixed, name = " \t\n　")
        character(mixed, name = "이름만")
        character(mixed, gender = com.knk.manyak.story.dto.SimpleStoryCharacterGender.FEMALE)
        val tagged = character(mixed)
        character(mixed, name = "주인공", role = com.knk.manyak.story.entity.StoryCreationCharacterRole.PROTAGONIST)
        repeat(2) { index ->
            val tag = creationTags.save(com.knk.manyak.story.entity.StoryCreationTag(
                category = com.knk.manyak.story.dto.SimpleStoryTagCategory.SUPPORTING_CHARACTER,
                name = "특징$index", tagSource = source))
            jdbc.update("INSERT INTO story_creation_session_tags(creation_session_id, character_id, tag_id, created_at) VALUES (?, ?, ?, ?)",
                mixed.id, tagged.id, tag.id, java.sql.Timestamp.from(now))
        }
        val emptyStory = story(title = "빈 칸만")
        val empty = session(emptyStory)
        repeat(2) { character(empty, name = " \t\n　") }
        val genre = creationTags.save(com.knk.manyak.story.entity.StoryCreationTag(
            category = com.knk.manyak.story.dto.SimpleStoryTagCategory.GENRE, name = "장르", tagSource = source))
        jdbc.update("INSERT INTO story_creation_session_tags(creation_session_id, tag_id, created_at) VALUES (?, ?, ?)",
            empty.id, genre.id, java.sql.Timestamp.from(now))
        val withoutSession = story(title = "세션 없음")
        for ((target, count) in listOf(mixedStory to 3, emptyStory to 0, withoutSession to null)) {
            val detailCount = json(get("/stories/${target.publicId}").expectStatus().isOk)["supportingCharacterCount"]
            val listCount = json(get("/stories?q=${target.publicId}").expectStatus().isOk)["items"][0]["supportingCharacterCount"]
            if (count == null) {
                assertThat(detailCount.isNull).isTrue()
                assertThat(listCount.isNull).isTrue()
            } else {
                assertThat(detailCount.asInt()).isEqualTo(count)
                assertThat(listCount.asInt()).isEqualTo(count)
            }
        }
        val points = json(get("/dashboard/charts?days=7").expectStatus().isOk)["distributions"]
            .first { it["chartId"].asText() == "characters" }["points"]
        assertThat(points.size()).isEqualTo(6)
        for ((count, point) in points.withIndex()) {
            val expected = if (count == 0 || count == 3) 1L else 0L
            val page = json(get("/stories?supportingCharacterCount=$count&creationFrom=2026-10-21&creationTo=2026-10-21").expectStatus().isOk)
            assertThat(page["totalElements"].asLong()).isEqualTo(expected)
            assertThat(point["value"].asDouble()).isEqualTo(expected.toDouble())
        }
    }

    @Test fun `직접 기간은 KST 양끝을 포함하고 링크와 빈 날짜를 같은 범위로 만든다`() {
        for (at in listOf(midnight.minusSeconds(86400), midnight.minusSeconds(1), midnight)) users.save(User(nickname = "경계", createdAt = at))
        val body = json(get("/dashboard/charts?from=2026-10-20&to=2026-10-20").expectStatus().isOk)
        assertThat(body["from"].asText()).isEqualTo("2026-10-20")
        assertThat(body["to"].asText()).isEqualTo("2026-10-20")
        assertThat(body["daily"].size()).isEqualTo(1)
        assertThat(body["daily"][0]["joined"].asLong()).isEqualTo(2)
        val links = body["distributions"][1]["points"][0]["link"]["params"]
        assertThat(links["from"].asText()).isEqualTo("2026-10-20")
        assertThat(links["to"].asText()).isEqualTo("2026-10-20")
        val full = json(get("/dashboard/charts?from=2025-10-21&to=2026-10-21").expectStatus().isOk)
        assertThat(full["daily"].size()).isEqualTo(366)
        assertThat(full["distributions"][7]["points"][0]["link"]["params"]["creationFrom"].asText()).isEqualTo("2025-10-21")
    }

    @Test fun `두 대시보드 기간은 양쪽 날짜 필수이고 366일 이내 과거 또는 오늘만 허용한다`() {
        for (path in listOf("/dashboard/charts", "/dashboard/analytics")) {
            for (query in listOf("from=2026-10-20", "to=2026-10-20", "days=7&from=2026-10-20&to=2026-10-21",
                "from=2026-10-21&to=2026-10-20", "from=2026-10-21&to=2026-10-22", "from=2025-10-20&to=2026-10-21",
                "from=2026-02-30&to=2026-10-21", "from=2026-1-01&to=2026-10-21", "from=&to=", "days=")) get("$path?$query").expectStatus().isBadRequest
        }
    }

    @Test fun `인물 수 분포의 모든 막대는 완성된 미삭제 스토리의 최신 세션 목록 총계와 같다`() {
        fun session(target: com.knk.manyak.story.entity.Story?, count: Int, complete: Boolean = true, created: Instant = midnight) {
            val row = creationSessions.save(com.knk.manyak.story.entity.StoryCreationSession(storyId = target?.id,
                status = if (complete) com.knk.manyak.story.entity.StoryCreationSessionStatus.STORY_CREATED else com.knk.manyak.story.entity.StoryCreationSessionStatus.STORYLINES_GENERATED,
                createdAt = created))
            repeat(count) { creationCharacters.save(com.knk.manyak.story.entity.StoryCreationCharacter(creationSession = row,
                role = com.knk.manyak.story.entity.StoryCreationCharacterRole.SUPPORTING_CHARACTER, name = "입력$it", sortOrder = (it + 1).toShort())) }
        }
        for (count in 0..5) {
            val target = story()
            if (count == 3) session(target, 0) // 목록은 같은 스토리의 최신 완료 세션만 읽는다.
            session(target, count)
        }
        session(null, 0, complete = false)
        session(null, 0)
        val removed = story()
        jdbc.update("UPDATE stories SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), removed.id)
        session(removed, 0)
        val outside = story()
        session(outside, 0)
        session(outside, 0, created = midnight.minusSeconds(10 * 86400))
        val body = json(get("/dashboard/charts?days=7").expectStatus().isOk)
        val points = body["distributions"].first { it["chartId"].asText() == "characters" }["points"]
        assertThat(points.size()).isEqualTo(6)
        for (point in points) {
            val link = point["link"]
            val response = client.get().uri { uri ->
                uri.path("/api/v1/admin/stories")
                link["params"].properties().forEach { (key, value) -> uri.queryParam(key, value.asText()) }
                uri.build()
            }.header("Authorization", "Bearer $token").exchange()
            val page = json(response.expectStatus().isOk)
            assertThat(page["totalElements"].asLong()).isEqualTo(1)
            assertThat(point["value"].asDouble()).isEqualTo(page["totalElements"].asDouble())
        }
    }

    @Test fun `제작 세션은 공식 계정을 포함하고 태그 인물 수와 기간 및 보류를 스토리에 연결한다`() {
        val team = member("공식"); val excluded = member("제외 팀")
        org.mockito.Mockito.doReturn(team.id).`when`(official).officialUserId()
        val story = story(team)
        val session = creationSessions.save(com.knk.manyak.story.entity.StoryCreationSession(userId = team.id, storyId = story.id,
            status = com.knk.manyak.story.entity.StoryCreationSessionStatus.STORY_CREATED, createdAt = midnight))
        repeat(2) { creationCharacters.save(com.knk.manyak.story.entity.StoryCreationCharacter(creationSession = session,
            role = com.knk.manyak.story.entity.StoryCreationCharacterRole.SUPPORTING_CHARACTER, name = "입력$it", sortOrder = (it + 1).toShort())) }
        creationSessions.save(com.knk.manyak.story.entity.StoryCreationSession(userId = excluded.id,
            status = com.knk.manyak.story.entity.StoryCreationSessionStatus.STORYLINES_GENERATED, createdAt = midnight))
        val tag = creationTags.save(com.knk.manyak.story.entity.StoryCreationTag(category = com.knk.manyak.story.dto.SimpleStoryTagCategory.GENRE,
            name = "판타지", tagSource = com.knk.manyak.story.entity.StoryCreationTagSource.PREDEFINED))
        jdbc.update("INSERT INTO story_creation_session_tags(creation_session_id, tag_id, created_at) VALUES (?, ?, ?)", session.id, tag.id, java.sql.Timestamp.from(now))
        val body = json(get("/stories?scope=original&creationFrom=2026-10-21&creationTo=2026-10-21&supportingCharacterCount=2").expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(1)
        assertThat(body["items"][0]["scope"].asText()).isEqualTo("original")
        assertThat(body["items"][0]["tags"][0].asText()).isEqualTo("판타지")
        val service = com.knk.manyak.admin.service.AdminChartsService(chartsRepository,
            com.knk.manyak.admin.config.AdminDashboardProperties(listOf(team.id, excluded.id)), official, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        assertThat(service.charts(7).distributions[7].points[2].value).isEqualTo(1.0)
        assertThat(service.charts(7).distributions[7].points[0].value).isZero()
        assertThat(json(get("/stories?scope=user").expectStatus().isOk)["totalElements"].asLong()).isZero()
        org.mockito.Mockito.doReturn(null).`when`(official).officialUserId()
        assertThat(json(get("/stories?scope=original").expectStatus().isOk)["totalElements"].asLong()).isZero()
    }

    @Test fun `스토리 보류 AI 기간과 채팅 기간은 AND이고 최근 채팅은 최대 다섯 개다`() {
        val owner = member("소유자")
        val target = story(owner, title = "대상 스토리")
        val other = story(owner, title = "조건 밖")
        repeat(7) { chat(target, owner, it, midnight.plusSeconds(it.toLong())) }
        chat(target, owner, 99, midnight.minusSeconds(1))
        val sub = com.knk.manyak.story.submission.StorySubmission(userId = owner.id, storyId = target.id,
            kind = com.knk.manyak.story.submission.SubmissionKind.UPDATE, payload = "{}", heldAt = now)
        submissionRepository.save(sub)
        aiLogs.save(com.knk.manyak.global.observability.aicall.AiCallLog(requestId = "story-filter", callerService = "test",
            feature = com.knk.manyak.global.observability.aicall.AiCallFeature.STORY_COMPLETION, storyId = target.id,
            status = com.knk.manyak.global.observability.aicall.AiCallStatus.FAILED, createdAt = now))
        val body = json(get("/stories?memberId=${owner.publicId}&moderationStatus=HELD&chatFrom=2026-10-21&chatTo=2026-10-21&aiFeature=story_completion&aiStatus=FAILED&aiFrom=2026-10-21&sort=chatCount").expectStatus().isOk)
        assertThat(body["totalElements"].asLong()).isEqualTo(1)
        assertThat(body["items"][0]["chatCount"].asLong()).isEqualTo(7)
        assertThat(body["items"][0]["totalTurns"].asLong()).isEqualTo(21)
        val detail = json(get("/stories/${target.publicId}").expectStatus().isOk)
        assertThat(detail["recentChats"].size()).isEqualTo(5)
        assertThat(detail["recentChats"][0]["turnCount"].asInt()).isEqualTo(6)
        assertThat(detail["chatCount"].asLong()).isEqualTo(8)
        assertThat(json(get("/stories?moderationStatus=APPROVED").expectStatus().isOk)["items"][0]["storyId"].asText()).isEqualTo(other.publicId.toString())
        for (q in listOf("대상", "소유자", target.publicId.toString())) assertThat(json(get("/stories?q=$q").expectStatus().isOk)["items"].size()).isGreaterThan(0)
        val deleted = chatRepository.save(com.knk.manyak.chat.entity.StoryChat(storyId = target.id, deletedAt = now))
        get("/chats/${deleted.publicId}").expectStatus().isNotFound
        assertThat(json(get("/chats?storyId=${target.publicId}").expectStatus().isOk)["totalElements"].asLong()).isEqualTo(8)
    }
    @Autowired private lateinit var submissionRepository: com.knk.manyak.story.submission.StorySubmissionRepository

    @Test fun `일별 지표는 팀을 모든 소유 활동에서 제외하고 삭제 행과 게스트와 KST 경계를 포함한다`() {
        val ordinary = member("일반 가입", created = midnight)
        val team = member("팀 가입", created = midnight)
        val before = member("이전 가입", created = midnight.minusSeconds(1))
        for (owner in listOf(ordinary, team)) {
            val story = story(owner)
            val chat = chat(story, owner)
            message(chat, 2, com.knk.manyak.chat.entity.MessageRole.ASSISTANT)
            ledger(owner, 250, "ATTENDANCE_REWARD", midnight)
            ledger(owner, 250, "ATTENDANCE_REWARD", midnight.plusSeconds(1))
            ledger(owner, -20, "CHAT_TURN", midnight)
            ledger(owner, -30, "EXPIRE", midnight)
            ledger(owner, 20, "REFUND", midnight)
            order(owner, completed = midnight, price = 4800)
            order(owner, com.knk.manyak.credit.entity.CreditOrderStatus.REFUNDED, completed = midnight, price = 2000, refund = now)
            jdbc.update("UPDATE story_chats SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), chat.id)
            jdbc.update("UPDATE stories SET deleted_at=? WHERE id=?", java.sql.Timestamp.from(now), story.id)
        }
        val guest = story()
        chat(guest)
        val service = com.knk.manyak.admin.service.AdminChartsService(chartsRepository,
            com.knk.manyak.admin.config.AdminDashboardProperties(listOf(team.id, admin.id)), official, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
        val today = service.charts(7).daily.last()
        assertThat(today.joined).isEqualTo(1)
        assertThat(today.chats).isEqualTo(2)
        assertThat(today.turns).isEqualTo(1)
        assertThat(today.stories).isEqualTo(2)
        assertThat(today.granted).isEqualTo(500)
        assertThat(today.spent).isEqualTo(20)
        assertThat(today.payments).isEqualTo(2)
        assertThat(today.revenue).isEqualTo(6800)
        assertThat(today.attendance).isEqualTo(1)
        assertThat(service.charts(7).daily[5].joined).isEqualTo(1)
    }

    @Test fun `모든 새 목록의 페이지 정렬과 필터 오류는 400이고 빈 페이지는 총계를 유지한다`() {
        for ((path, queries) in mapOf(
            "/stories" to listOf("scope=bad", "visibility=SECRET", "moderationStatus=BAD", "supportingCharacterCount=6", "aiFeature=chat_response", "aiStatus=BAD", "memberId=bad", "creationFrom=2026-10-22&creationTo=2026-10-21"),
            "/chats" to listOf("turnBucket=-1", "turnBucket=13", "hasFirstMessage=bad", "storyId=bad", "memberId=bad", "aiFeature=story_completion", "turnDate=2026-02-30", "from=2026-10-22&to=2026-10-21"),
            "/members" to listOf("chatBucket=5", "excludeDeleted=bad", "creditDirection=other", "attendanceDate=bad", "chatFrom=2026-10-22&chatTo=2026-10-21"),
            "/payments/orders" to listOf("memberId=bad"))) {
            for (query in queries + listOf("q=", "q=${"x".repeat(51)}", "sort=bad", "page=0", "size=101", "cursor=", "limit=")) {
                get("$path?$query").expectStatus().isBadRequest
            }
        }
        for (path in listOf("/members/${admin.publicId}/transactions", "/members/${admin.publicId}/attendance")) {
            get("$path?sort=bad").expectStatus().isBadRequest
            get("$path?cursor=anything").expectStatus().isBadRequest
            assertThat(json(get("$path?page=999").expectStatus().isOk)["totalPages"].asLong()).isZero()
        }
        for (path in listOf("/stories", "/chats")) assertThat(json(get("$path?page=999").expectStatus().isOk)["items"].size()).isZero()
        for (suffix in listOf("transactions", "attendance")) get("/members/${UUID.randomUUID()}/$suffix").expectStatus().isNotFound
    }

    @Test fun `관리자 상세 응답 스키마를 포함한 전체 OpenAPI 문서가 직렬화된다`() {
        val body = json(client.get().uri("/v3/api-docs").exchange().expectStatus().isOk)
        val schemas = body["components"]["schemas"]
        val operation = body["paths"]["/api/v1/admin/credits/transactions"]["get"]
        assertThat(operation).isNotNull()
        assertThat(operation["parameters"].toList().map { it["name"].asText() }).contains("direction", "date", "sort", "page", "size", "order").doesNotContain("memberId")
        assertThat(operation["responses"].has("200")).isTrue()
        assertThat(operation["security"][0].has("bearerAuth")).isTrue()
        assertThat(schemas["AdminCreditTransactionResponse"]["properties"].has("member")).isTrue()
        assertThat(schemas["AdminCreditTransactionResponse"]["properties"].has("balanceAfter")).isFalse()

        assertThat(schemas["AdminStoryDetailResponse"]["properties"].has("retention")).isTrue()
        assertThat(schemas["AdminMemberDetailResponse"]["properties"].has("creditBalance")).isTrue()
        assertThat(schemas["AdminPaymentDetailResponse"]["properties"].has("firstPaidAt")).isTrue()
    }

    @Test fun `SQL 와일드카드는 닉네임 검색에서 문자로 취급한다`() {
        member("다른사람")
        member("100%_!")
        val body = json(client.get().uri { builder -> builder.path("/api/v1/admin/members").queryParam("q", "%_").build() }
            .header("Authorization", "Bearer $token").exchange().expectStatus().isOk)
        assertThat(body["items"].size()).isEqualTo(1)
        assertThat(body["items"][0]["nickname"].asText()).isEqualTo("100%_!")
    }
}

@org.springframework.boot.test.context.TestConfiguration
class AdminReadClockConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    fun adminReadClock(): java.time.Clock = java.time.Clock.fixed(Instant.parse("2026-10-20T15:30:00Z"), java.time.ZoneOffset.UTC)
}
